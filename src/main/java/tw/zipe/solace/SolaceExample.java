package tw.zipe.solace;

import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.ConsumerFlowProperties;
import com.solacesystems.jcsmp.FlowReceiver;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPStreamingPublishEventHandler;
import com.solacesystems.jcsmp.Queue;
import com.solacesystems.jcsmp.SDTMap;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.Topic;
import com.solacesystems.jcsmp.XMLMessageConsumer;
import com.solacesystems.jcsmp.XMLMessageListener;
import com.solacesystems.jcsmp.XMLMessageProducer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Solace 訊息系統 Java 連接範例
 * 包含基本的連接、發送和接收訊息功能
 */
public class SolaceExample {

    // Solace 連接參數
    private static final String HOST = "tcp://localhost:55554";
    private static final String USERNAME = "default";
    private static final String PASSWORD = "default";
    private static final String VPN_NAME = "default";
    private static final String TOPIC_NAME = "tutorial/topic";
    private static final String QUEUE_NAME = "tutorial/queue";

    private JCSMPSession session;
    private XMLMessageProducer producer;
    private XMLMessageConsumer consumer;

    /**
     * 初始化 Solace 連接
     */
    public void initialize() throws JCSMPException {
        System.out.println("正在連接到 Solace...");

        // 建立 JCSMPProperties 物件
        JCSMPProperties properties = new JCSMPProperties();
        properties.setProperty(JCSMPProperties.HOST, HOST);
        properties.setProperty(JCSMPProperties.USERNAME, USERNAME);
        properties.setProperty(JCSMPProperties.PASSWORD, PASSWORD);
        properties.setProperty(JCSMPProperties.VPN_NAME, VPN_NAME);

        // 建立 session
        session = JCSMPFactory.onlyInstance().createSession(properties);
        session.connect();

        System.out.println("成功連接到 Solace!");
    }

    /**
     * 發送訊息到 Topic
     */
    public void sendTopicMessage(String message) throws JCSMPException {
        if (producer == null) {
            producer = session.getMessageProducer(new JCSMPStreamingPublishEventHandler() {
                @Override
                public void responseReceived(String messageID) {
                    System.out.println("訊息已確認發送，ID: " + messageID);
                }

                @Override
                public void handleError(String messageID, JCSMPException e, long timestamp) {
                    System.err.println("發送訊息失敗: " + e.getMessage());
                }
            });
        }

        // 建立 topic 物件
        Topic topic = JCSMPFactory.onlyInstance().createTopic(TOPIC_NAME);

        // 建立訊息
        TextMessage msg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
        msg.setText(message);

        // 發送訊息
        producer.send(msg, topic);
        System.out.println("已發送訊息到 topic '" + TOPIC_NAME + "': " + message);
    }

    /**
     * 發送訊息到 Queue
     */
    public void sendQueueMessage(String message) throws JCSMPException {
        if (producer == null) {
            producer = session.getMessageProducer(new JCSMPStreamingPublishEventHandler() {
                @Override
                public void responseReceived(String messageID) {
                    System.out.println("訊息已確認發送，ID: " + messageID);
                }

                @Override
                public void handleError(String messageID, JCSMPException e, long timestamp) {
                    System.err.println("發送訊息失敗: " + e.getMessage());
                }
            });
        }

        // 建立 queue 物件
        Queue queue = JCSMPFactory.onlyInstance().createQueue(QUEUE_NAME);

        // 建立訊息
        TextMessage msg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
        msg.setText(message);

        // 發送訊息
        producer.send(msg, queue);
        System.out.println("已發送訊息到 queue '" + QUEUE_NAME + "': " + message);
    }

    /**
     * 訂閱 Topic 並接收訊息
     */
    public void subscribeToTopic() throws JCSMPException {
        // 建立 topic 物件
        Topic topic = JCSMPFactory.onlyInstance().createTopic(TOPIC_NAME);

        // 建立 consumer
        consumer = session.getMessageConsumer(new XMLMessageListener() {
            @Override
            public void onReceive(BytesXMLMessage message) {
                if (message instanceof TextMessage) {
                    TextMessage textMsg = (TextMessage) message;
                    System.out.println("收到 Topic 訊息: " + textMsg.getText());
                } else {
                    System.out.println("收到 Topic 訊息: " + message.dump());
                }
            }

            @Override
            public void onException(JCSMPException e) {
                System.err.println("Consumer 發生異常: " + e.getMessage());
            }
        });

        // 訂閱 topic
        session.addSubscription(topic);
        consumer.start();
        System.out.println("已訂閱 topic: " + TOPIC_NAME);
    }

    /**
     * 從 Queue 接收訊息
     */
    public void receiveFromQueue() throws JCSMPException {
        // 建立 queue 物件
        Queue queue = JCSMPFactory.onlyInstance().createQueue(QUEUE_NAME);

        // 設置 ConsumerFlowProperties
        ConsumerFlowProperties flowProperties = new ConsumerFlowProperties();
        flowProperties.setEndpoint(queue);

        // 建立 flow receiver
        FlowReceiver flowReceiver = session.createFlow(new XMLMessageListener() {
            @Override
            public void onReceive(BytesXMLMessage message) {
                if (message instanceof TextMessage) {
                    TextMessage textMsg = (TextMessage) message;
                    System.out.println("收到 Queue 訊息: " + textMsg.getText());
                } else {
                    System.out.println("收到 Queue 訊息: " + message.dump());
                }

                // 確認訊息
                message.ackMessage();
            }

            @Override
            public void onException(JCSMPException e) {
                System.err.println("Flow Receiver 發生異常: " + e.getMessage());
            }
        }, flowProperties);

        // 開始接收
        flowReceiver.start();
        System.out.println("已開始從 queue 接收訊息: " + QUEUE_NAME);
    }

    /**
     * 發送檔案到 Topic
     */
    public void sendFileToTopic(String filePath) throws JCSMPException, IOException {
        if (producer == null) {
            producer = session.getMessageProducer(new JCSMPStreamingPublishEventHandler() {
                @Override
                public void responseReceived(String messageID) {
                    System.out.println("檔案已確認發送，ID: " + messageID);
                }

                @Override
                public void handleError(String messageID, JCSMPException e, long timestamp) {
                    System.err.println("發送檔案失敗: " + e.getMessage());
                }
            });
        }

        // 建立 topic 物件
        Topic topic = JCSMPFactory.onlyInstance().createTopic(TOPIC_NAME + "/file");

        // 讀取檔案內容
        File file = new File(filePath);
        byte[] fileData = Files.readAllBytes(file.toPath());

        // 建立 BytesMessage
        BytesXMLMessage msg = JCSMPFactory.onlyInstance().createMessage(BytesXMLMessage.class);
        msg.writeAttachment(fileData);

        // 建立 SDTMap 用於存儲檔案元數據
        SDTMap properties = JCSMPFactory.onlyInstance().createMap();
        properties.putString("FILE_NAME", file.getName());
        properties.putLong("FILE_SIZE", file.length());
        properties.putString("FILE_TYPE", getFileExtension(file.getName()));
        msg.setProperties(properties);

        // 發送訊息
        producer.send(msg, topic);
        System.out.println("已發送檔案到 topic '" + TOPIC_NAME + "/file': " + file.getName() + " (" + fileData.length + " bytes)");
    }

    /**
     * 發送檔案到 Queue
     */
    public void sendFileToQueue(String filePath) throws JCSMPException, IOException {
        if (producer == null) {
            producer = session.getMessageProducer(new JCSMPStreamingPublishEventHandler() {
                @Override
                public void responseReceived(String messageID) {
                    System.out.println("檔案已確認發送，ID: " + messageID);
                }

                @Override
                public void handleError(String messageID, JCSMPException e, long timestamp) {
                    System.err.println("發送檔案失敗: " + e.getMessage());
                }
            });
        }

        // 建立 queue 物件
        Queue queue = JCSMPFactory.onlyInstance().createQueue(QUEUE_NAME + ".file");

        // 讀取檔案內容
        File file = new File(filePath);
        byte[] fileData = Files.readAllBytes(file.toPath());

        // 建立 BytesMessage
        BytesXMLMessage msg = JCSMPFactory.onlyInstance().createMessage(BytesXMLMessage.class);
        msg.writeAttachment(fileData);

        // 建立 SDTMap 用於存儲檔案元數據
        SDTMap properties = JCSMPFactory.onlyInstance().createMap();
        properties.putString("FILE_NAME", file.getName());
        properties.putLong("FILE_SIZE", file.length());
        properties.putString("FILE_TYPE", getFileExtension(file.getName()));
        msg.setProperties(properties);

        // 發送訊息
        producer.send(msg, queue);
        System.out.println("已發送檔案到 queue '" + QUEUE_NAME + ".file': " + file.getName() + " (" + fileData.length + " bytes)");
    }

    /**
     * 從 Topic 接收檔案
     */
    public void receiveFileFromTopic(String saveDirectory) throws JCSMPException {
        // 建立保存目錄
        File directory = new File(saveDirectory);
        if (!directory.exists()) {
            directory.mkdirs();
        }

        // 建立 topic 物件
        Topic topic = JCSMPFactory.onlyInstance().createTopic(TOPIC_NAME + "/file");

        // 建立 consumer
        XMLMessageConsumer fileConsumer = session.getMessageConsumer(new XMLMessageListener() {
            @Override
            public void onReceive(BytesXMLMessage message) {
                try {
                    // 獲取檔案元數據
                    SDTMap properties = message.getProperties();
                    if (properties != null) {
                        String fileName = properties.getString("FILE_NAME");
                        long fileSize = properties.getLong("FILE_SIZE");

                        // 獲取檔案內容
                        byte[] fileData = message.getAttachmentByteBuffer().array();

                        // 保存檔案
                        File outputFile = new File(directory, fileName);
                        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                            fos.write(fileData);
                        }

                        System.out.println("已保存檔案: " + outputFile.getAbsolutePath() + " (" + fileSize + " bytes)");
                    }
                } catch (Exception e) {
                    System.err.println("處理檔案訊息時出錯: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            @Override
            public void onException(JCSMPException e) {
                System.err.println("檔案接收發生異常: " + e.getMessage());
            }
        });

        // 訂閱 topic
        session.addSubscription(topic);
        fileConsumer.start();
        System.out.println("已訂閱檔案 topic: " + TOPIC_NAME + "/file");
    }

    /**
     * 從 Queue 接收檔案
     */
    public void receiveFileFromQueue(String saveDirectory) throws JCSMPException {
        // 建立保存目錄
        File directory = new File(saveDirectory);
        if (!directory.exists()) {
            directory.mkdirs();
        }

        // 建立 queue 物件
        Queue queue = JCSMPFactory.onlyInstance().createQueue(QUEUE_NAME + ".file");

        // 設置 ConsumerFlowProperties
        ConsumerFlowProperties flowProperties = new ConsumerFlowProperties();
        flowProperties.setEndpoint(queue);

        // 建立 flow receiver
        FlowReceiver fileReceiver = session.createFlow(new XMLMessageListener() {
            @Override
            public void onReceive(BytesXMLMessage message) {
                try {
                    // 獲取檔案元數據
                    SDTMap properties = message.getProperties();
                    if (properties != null) {
                        String fileName = properties.getString("FILE_NAME");
                        long fileSize = properties.getLong("FILE_SIZE");

                        // 獲取檔案內容
                        byte[] fileData = message.getAttachmentByteBuffer().array();

                        // 保存檔案
                        File outputFile = new File(directory, fileName);
                        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                            fos.write(fileData);
                        }

                        System.out.println("已保存檔案: " + outputFile.getAbsolutePath() + " (" + fileSize + " bytes)");
                    }

                    // 確認訊息
                    message.ackMessage();
                } catch (Exception e) {
                    System.err.println("處理檔案訊息時出錯: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            @Override
            public void onException(JCSMPException e) {
                System.err.println("檔案接收發生異常: " + e.getMessage());
            }
        }, flowProperties);

        // 開始接收
        fileReceiver.start();
        System.out.println("已開始從 queue 接收檔案: " + QUEUE_NAME + ".file");
    }

    /**
     * 獲取檔案擴展名
     */
    private String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < fileName.length() - 1) {
            return fileName.substring(dotIndex + 1);
        }
        return "";
    }

    /**
     * 關閉連接
     */
    public void close() {
        if (producer != null) {
            producer.close();
        }

        if (consumer != null) {
            consumer.close();
        }

        if (session != null) {
            session.closeSession();
        }

        System.out.println("已關閉 Solace 連接");
    }

    /**
     * 主程式
     */
    public static void main(String[] args) {
        SolaceExample example = new SolaceExample();
        String fileToSend = "D:/tmp/SimpleReporter.pdf";  // 要發送的檔案路徑
        String saveDirectory = "received_files";      // 接收檔案的目錄

        try {
            // 初始化連接
            example.initialize();

            // 訂閱 Topic
            example.subscribeToTopic();

            // 訂閱檔案 Topic
            example.receiveFileFromTopic(saveDirectory);

            // 從 Queue 接收訊息
            example.receiveFromQueue();

            // 從 Queue 接收檔案
            example.receiveFileFromQueue(saveDirectory);

            // 等待一段時間讓訂閱生效
            Thread.sleep(1000);

            // 發送訊息到 Topic
            example.sendTopicMessage("Hello from Topic!");

            // 發送訊息到 Queue
            example.sendQueueMessage("Hello from Queue!");

            // 發送檔案到 Topic
            example.sendFileToTopic(fileToSend);

            // 發送檔案到 Queue
            example.sendFileToQueue(fileToSend);

            // 持續運行 20 秒以接收訊息
            System.out.println("等待接收訊息和檔案... (20 秒)");
            Thread.sleep(20000);

        } catch (Exception e) {
            System.err.println("發生錯誤: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // 關閉連接
            example.close();
        }
    }
}
