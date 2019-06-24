/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jd.joyqueue.broker.protocol.handler;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.jd.joyqueue.broker.BrokerContext;
import com.jd.joyqueue.broker.BrokerContextAware;
import com.jd.joyqueue.broker.cluster.ClusterManager;
import com.jd.joyqueue.broker.consumer.Consume;
import com.jd.joyqueue.broker.consumer.model.PullResult;
import com.jd.joyqueue.broker.helper.SessionHelper;
import com.jd.joyqueue.broker.network.traffic.Traffic;
import com.jd.joyqueue.broker.protocol.JoyQueueCommandHandler;
import com.jd.joyqueue.broker.protocol.command.FetchPartitionMessageResponse;
import com.jd.joyqueue.broker.protocol.converter.CheckResultConverter;
import com.jd.joyqueue.domain.TopicName;
import com.jd.joyqueue.exception.JoyQueueCode;
import com.jd.joyqueue.exception.JoyQueueException;
import com.jd.joyqueue.network.command.BooleanAck;
import com.jd.joyqueue.network.command.FetchPartitionMessageAckData;
import com.jd.joyqueue.network.command.FetchPartitionMessageData;
import com.jd.joyqueue.network.command.FetchPartitionMessageRequest;
import com.jd.joyqueue.network.command.JoyQueueCommandType;
import com.jd.joyqueue.network.session.Connection;
import com.jd.joyqueue.network.session.Consumer;
import com.jd.joyqueue.network.transport.Transport;
import com.jd.joyqueue.network.transport.command.Command;
import com.jd.joyqueue.network.transport.command.Type;
import com.jd.joyqueue.response.BooleanResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;

/**
 * FetchPartitionMessageRequestHandler
 * author: gaohaoxiang
 * email: gaohaoxiang@jd.com
 * date: 2018/12/13
 */
public class FetchPartitionMessageRequestHandler implements JoyQueueCommandHandler, Type, BrokerContextAware {

    protected static final Logger logger = LoggerFactory.getLogger(FetchPartitionMessageRequestHandler.class);

    private Consume consume;
    private ClusterManager clusterManager;

    @Override
    public void setBrokerContext(BrokerContext brokerContext) {
        this.consume = brokerContext.getConsume();
        this.clusterManager = brokerContext.getClusterManager();
    }

    @Override
    public Command handle(Transport transport, Command command) {
        FetchPartitionMessageRequest fetchPartitionMessageRequest = (FetchPartitionMessageRequest) command.getPayload();
        Connection connection = SessionHelper.getConnection(transport);

        if (connection == null || !connection.isAuthorized(fetchPartitionMessageRequest.getApp())) {
            logger.warn("connection is not exists, transport: {}, app: {}", transport, fetchPartitionMessageRequest.getApp());
            return BooleanAck.build(JoyQueueCode.FW_CONNECTION_NOT_EXISTS.getCode());
        }

        Table<String, Short, FetchPartitionMessageAckData> result = HashBasedTable.create();
        Traffic traffic = new Traffic(fetchPartitionMessageRequest.getApp());

        for (Map.Entry<String, Map<Short, FetchPartitionMessageData>> entry : fetchPartitionMessageRequest.getPartitions().rowMap().entrySet()) {
            String topic = entry.getKey();
            Consumer consumer = new Consumer(connection.getId(), topic, fetchPartitionMessageRequest.getApp(), Consumer.ConsumeType.JMQ);
            for (Map.Entry<Short, FetchPartitionMessageData> partitionEntry : entry.getValue().entrySet()) {
                short partition = partitionEntry.getKey();

                BooleanResponse checkResult = clusterManager.checkReadable(TopicName.parse(topic), fetchPartitionMessageRequest.getApp(),
                        connection.getHost(), partition);
                if (!checkResult.isSuccess()) {
                    logger.warn("checkReadable failed, transport: {}, topic: {}, partition: {}, app: {}, code: {}", transport,
                            consumer.getTopic(), partition, consumer.getApp(), checkResult.getJoyQueueCode());
                    buildFetchPartitionMessageAckData(topic, entry.getValue(), CheckResultConverter.convertFetchCode(checkResult.getJoyQueueCode()), result);
                    traffic.record(topic, 0);
                    continue;
                }

                FetchPartitionMessageData fetchPartitionMessageData = partitionEntry.getValue();
                FetchPartitionMessageAckData fetchPartitionMessageAckData = fetchMessage(transport, consumer, partition,
                        fetchPartitionMessageData.getIndex(), fetchPartitionMessageData.getCount());
                result.put(topic, partitionEntry.getKey(), fetchPartitionMessageAckData);
                traffic.record(topic, fetchPartitionMessageAckData.getSize());
            }
        }

        FetchPartitionMessageResponse fetchPartitionMessageResponse = new FetchPartitionMessageResponse();
        fetchPartitionMessageResponse.setTraffic(traffic);
        fetchPartitionMessageResponse.setData(result);
        return new Command(fetchPartitionMessageResponse);
    }

    protected void buildFetchPartitionMessageAckData(String topic, Map<Short, FetchPartitionMessageData> partitionMap, JoyQueueCode code, Table<String, Short, FetchPartitionMessageAckData> result) {
        FetchPartitionMessageAckData fetchPartitionMessageAckData = new FetchPartitionMessageAckData(code);
        for (Map.Entry<Short, FetchPartitionMessageData> entry : partitionMap.entrySet()) {
            result.put(topic, entry.getKey(), fetchPartitionMessageAckData);
        }
    }

    protected FetchPartitionMessageAckData fetchMessage(Transport transport, Consumer consumer, short partition, long index, int count) {
        FetchPartitionMessageAckData fetchPartitionMessageAckData = new FetchPartitionMessageAckData();
        fetchPartitionMessageAckData.setBuffers(Collections.emptyList());
        try {
            if (index == FetchPartitionMessageRequest.NONE_INDEX) {
                index = consume.getAckIndex(consumer, partition);
            }
            if (index < consume.getMinIndex(consumer, partition) || index > consume.getMaxIndex(consumer, partition)) {
                logger.warn("fetchPartitionMessage exception, index ou of range, transport: {}, consumer: {}, partition: {}, index: {}", transport, consumer, partition, index);
                fetchPartitionMessageAckData.setCode(JoyQueueCode.FW_FETCH_MESSAGE_INDEX_OUT_OF_RANGE);
            } else {
                PullResult pullResult = consume.getMessage(consumer, partition, index, count);
                if (!pullResult.getJoyQueueCode().equals(JoyQueueCode.SUCCESS)) {
                    logger.error("fetchPartitionMessage exception, transport: {}, consumer: {}, partition: {}, index: {}", transport, consumer, partition, index);
                }
                fetchPartitionMessageAckData.setBuffers(pullResult.getBuffers());
                fetchPartitionMessageAckData.setCode(pullResult.getJoyQueueCode());
            }
        } catch (JoyQueueException e) {
            logger.error("fetchPartitionMessage exception, transport: {}, consumer: {}, partition: {}, index: {}", transport, consumer, partition, index, e);
            fetchPartitionMessageAckData.setCode(JoyQueueCode.valueOf(e.getCode()));
        } catch (Exception e) {
            logger.error("fetchPartitionMessage exception, transport: {}, consumer: {}, partition: {}, index: {}", transport, consumer, partition, index, e);
            fetchPartitionMessageAckData.setCode(JoyQueueCode.CN_UNKNOWN_ERROR);
        }
        return fetchPartitionMessageAckData;
    }

    @Override
    public int type() {
        return JoyQueueCommandType.FETCH_PARTITION_MESSAGE_REQUEST.getCode();
    }
}