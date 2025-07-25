package tw.zipe.solace.service;

import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.ConsumerFlowProperties;
import com.solacesystems.jcsmp.FlowReceiver;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.Queue;
import com.solacesystems.jcsmp.SDTMap;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.Topic;
import com.solacesystems.jcsmp.XMLMessageConsumer;
import com.solacesystems.jcsmp.XMLMessageListener;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tw.zipe.solace.config.SolaceProperties;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class SolaceConsumerService {

    private static final Logger logger = LoggerFactory.getLogger(SolaceConsumerService.class);

    private final SolaceConnectionPool consumerPool;
    private final SolaceProperties solaceProperties;

    private final List<ConsumerInfo> consumerInfos = new CopyOnWriteArrayList<>();
    private final Map<String, ConcurrentLinkedQueue<String>> topicMessagesCache = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedQueue<String>> queueMessagesCache = new ConcurrentHashMap<>();

    public SolaceConsumerService(SolaceProperties solaceProperties) {
        this.solaceProperties = solaceProperties;
        this.consumerPool = new SolaceConnectionPool(new SolaceSessionFactory(solaceProperties), solaceProperties.getPool());
    }

    public void subscribeToTopic(String topicName) throws Exception {
        Topic topic = JCSMPFactory.onlyInstance().createTopic(topicName);
        JCSMPSession session = consumerPool.getSession();
        try {
            XMLMessageConsumer consumer = session.getMessageConsumer(new XMLMessageListener() {
                @Override
                public void onReceive(BytesXMLMessage msg) {
                    handleReceivedMessage(msg, topicName, SolaceService.DestinationType.TOPIC);
                }

                @Override
                public void onException(JCSMPException e) {
                    logger.error("消費者在 Topic {} 上發生異常: {}", topicName, e.getMessage(), e);
                }
            });
            session.addSubscription(topic);
            consumer.start();
            consumerInfos.add(new ConsumerInfo(session, consumer, null));
            logger.info("已成功訂閱 Topic: {}", topicName);
        } catch (Exception e) {
            consumerPool.returnSession(session);
            throw e;
        }
    }

    public void receiveFromQueue(String queueName) throws Exception {
        Queue queue = JCSMPFactory.onlyInstance().createQueue(queueName);
        JCSMPSession session = consumerPool.getSession();
        try {
            ConsumerFlowProperties flowProps = new ConsumerFlowProperties();
            flowProps.setEndpoint(queue);
            flowProps.setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT);

            FlowReceiver flowReceiver = session.createFlow(new XMLMessageListener() {
                @Override
                public void onReceive(BytesXMLMessage msg) {
                    handleReceivedMessage(msg, queueName, SolaceService.DestinationType.QUEUE);
                    msg.ackMessage();
                }

                @Override
                public void onException(JCSMPException e) {
                    logger.error("Queue {} 消費者發生異常: {}", queueName, e.getMessage(), e);
                }
            }, flowProps);

            flowReceiver.start();
            consumerInfos.add(new ConsumerInfo(session, null, flowReceiver));
            logger.info("已開始監聽 Queue: {}", queueName);
        } catch (Exception e) {
            consumerPool.returnSession(session);
            throw e;
        }
    }

    private void handleReceivedMessage(BytesXMLMessage msg, String source, SolaceService.DestinationType type) {
        if (msg instanceof TextMessage textMessage) {
            String messageText = textMessage.getText();
            logger.info("收到來自 {} '{}' 的文字訊息: {}", type, source, messageText);
            ConcurrentLinkedQueue<String> cache = (type == SolaceService.DestinationType.TOPIC) ?
                    topicMessagesCache.computeIfAbsent(source, k -> new ConcurrentLinkedQueue<>()) :
                    queueMessagesCache.computeIfAbsent(source, k -> new ConcurrentLinkedQueue<>());
            cache.add(messageText);
        } else if (msg.hasAttachment()) {
            try {
                SDTMap properties = msg.getProperties();
                if (properties == null || !properties.containsKey("FILE_NAME")) {
                    logger.warn("收到的檔案訊息缺少 'FILE_NAME' 屬性，將忽略此訊息。來源: {}", source);
                    return;
                }
                final String fileName = properties.getString("FILE_NAME");
                Path directoryPath = Paths.get(solaceProperties.getReceived().getFiles().getDirectory());
                Files.createDirectories(directoryPath);

                Path outputPath = directoryPath.resolve(fileName);
                ByteBuffer buffer = msg.getAttachmentByteBuffer();

                try (FileChannel fileChannel = FileChannel.open(outputPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    fileChannel.write(buffer);
                }

                String successMessage = String.format("檔案 '%s' 已成功接收並儲存至: %s", fileName, outputPath.toAbsolutePath());
                logger.info(successMessage);
                ConcurrentLinkedQueue<String> cache = (type == SolaceService.DestinationType.TOPIC) ?
                        topicMessagesCache.computeIfAbsent(source, k -> new ConcurrentLinkedQueue<>()) :
                        queueMessagesCache.computeIfAbsent(source, k -> new ConcurrentLinkedQueue<>());
                cache.add(successMessage);
            } catch (IOException | JCSMPException e) {
                logger.error("處理來自 {} '{}' 的檔案訊息時發生錯誤", type, source, e);
            }
        }
    }

    public List<String> getAndClearTopicMessages(String topicName) {
        return getAndClearMessagesFromCache(topicMessagesCache, topicName);
    }

    public List<String> getAndClearQueueMessages(String queueName) {
        return getAndClearMessagesFromCache(queueMessagesCache, queueName);
    }

    private List<String> getAndClearMessagesFromCache(Map<String, ConcurrentLinkedQueue<String>> cache, String key) {
        ConcurrentLinkedQueue<String> queue = cache.get(key);
        if (queue == null) {
            return new ArrayList<>();
        }
        List<String> messages = new ArrayList<>();
        String message;
        while ((message = queue.poll()) != null) {
            messages.add(message);
        }
        return messages;
    }

    @PreDestroy
    public void close() {
        logger.info("正在關閉 Solace 消費者資源...");
        for (ConsumerInfo info : consumerInfos) {
            info.close();
        }
        consumerInfos.clear();
        consumerPool.close();
        logger.info("Solace 消費者連線池已關閉。");
    }

    private static class ConsumerInfo {
        private final JCSMPSession session;
        private final XMLMessageConsumer consumer;
        private final FlowReceiver flowReceiver;

        public ConsumerInfo(JCSMPSession session, XMLMessageConsumer consumer, FlowReceiver flowReceiver) {
            this.session = session;
            this.consumer = consumer;
            this.flowReceiver = flowReceiver;
        }

        public void close() {
            if (flowReceiver != null) {
                flowReceiver.stop();
            }
            if (consumer != null) {
                consumer.close();
            }
            if (session != null && !session.isClosed()) {
                session.closeSession();
            }
        }
    }
}
