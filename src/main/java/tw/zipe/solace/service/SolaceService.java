package tw.zipe.solace.service;

import org.springframework.stereotype.Service;
import tw.zipe.solace.config.SolaceProperties;

import java.util.List;

@Service
public class SolaceService {

    private final SolaceProducerService producerService;
    private final SolaceConsumerService consumerService;
    private final SolaceProperties solaceProperties;

    public SolaceService(SolaceProducerService producerService, SolaceConsumerService consumerService, SolaceProperties solaceProperties) {
        this.producerService = producerService;
        this.consumerService = consumerService;
        this.solaceProperties = solaceProperties;
    }

    public void sendTextMessage(String destinationName, DestinationType type, String message) throws Exception {
        producerService.sendTextMessage(destinationName, type, message);
    }

    public void sendFile(String destinationName, DestinationType type, String fileName, byte[] fileData) throws Exception {
        producerService.sendFile(destinationName, type, fileName, fileData);
    }

    public void subscribeToTopic(String topicName) throws Exception {
        consumerService.subscribeToTopic(topicName);
    }

    public void receiveFromQueue(String queueName) throws Exception {
        consumerService.receiveFromQueue(queueName);
    }

    public List<String> getAndClearTopicMessages(String topicName) {
        return consumerService.getAndClearTopicMessages(topicName);
    }

    public List<String> getAndClearQueueMessages(String queueName) {
        return consumerService.getAndClearQueueMessages(queueName);
    }

    public String getDefaultTopic() {
        return solaceProperties.getDefaults().getTopic();
    }

    public String getDefaultQueue() {
        return solaceProperties.getDefaults().getQueue();
    }

    public enum DestinationType {
        TOPIC, QUEUE
    }
}

