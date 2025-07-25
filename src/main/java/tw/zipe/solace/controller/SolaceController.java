package tw.zipe.solace.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.HandlerMapping;
import tw.zipe.solace.service.SolaceService;

import java.util.List;

/**
 * Solace 訊息發布與訂閱的 RESTful API 控制器
 * <p>
 * 提供 HTTP 端點來與 Solace 訊息系統進行互動，包括發送文字訊息、上傳檔案、訂閱 Topic 以及監聽 Queue。
 * </p>
 */
@RestController
@RequestMapping("/api/solace")
public class SolaceController {

    private static final Logger logger = LoggerFactory.getLogger(SolaceController.class);
    private final SolaceService solaceService;

    @Autowired
    public SolaceController(SolaceService solaceService) {
        this.solaceService = solaceService;
    }

    private String getDestinationName(HttpServletRequest request, String defaultName) {
        String path = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String bestMatchPattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (bestMatchPattern != null) {
            String prefix = bestMatchPattern.replace("/**", "");
            String subPath = path.substring(prefix.length());
            if (subPath.startsWith("/")) {
                subPath = subPath.substring(1);
            }
            if (!subPath.isEmpty()) {
                return subPath;
            }
        }
        return defaultName;
    }

    @PostMapping(value = {"/topic", "/topic/**"})
    public ResponseEntity<String> publishToTopic(HttpServletRequest request, @RequestBody String message) throws Exception {
        String topicName = getDestinationName(request, solaceService.getDefaultTopic());
        logger.info("接收到發布訊息到 Topic '{}' 的請求。", topicName);
        solaceService.sendTextMessage(topicName, SolaceService.DestinationType.TOPIC, message);
        String responseMessage = "訊息已成功發送到 Topic: " + topicName;
        logger.info("成功處理發布訊息到 Topic '{}' 的請求。", topicName);
        return ResponseEntity.ok(responseMessage);
    }

    @PostMapping(value = {"/queue", "/queue/**"})
    public ResponseEntity<String> publishToQueue(HttpServletRequest request, @RequestBody String message) throws Exception {
        String queueName = getDestinationName(request, solaceService.getDefaultQueue());
        logger.info("接收到發布訊息到 Queue '{}' 的請求。", queueName);
        solaceService.sendTextMessage(queueName, SolaceService.DestinationType.QUEUE, message);
        String responseMessage = "訊息已成功發送到 Queue: " + queueName;
        logger.info("成功處理發布訊息到 Queue '{}' 的請求。", queueName);
        return ResponseEntity.ok(responseMessage);
    }

    @PostMapping(value = {"/topic/file", "/topic/file/**"})
    public ResponseEntity<String> uploadFileToTopic(HttpServletRequest request, @RequestParam("file") MultipartFile file) throws Exception {
        String topicName = getDestinationName(request, solaceService.getDefaultTopic());
        logger.info("接收到上傳檔案 '{}' 到 Topic '{}' 的請求。", file.getOriginalFilename(), topicName);
        solaceService.sendFile(topicName, SolaceService.DestinationType.TOPIC, file.getOriginalFilename(), file.getBytes());
        String responseMessage = "檔案已成功發送到 Topic: " + topicName;
        logger.info("成功處理上傳檔案到 Topic '{}' 的請求。", topicName);
        return ResponseEntity.ok(responseMessage);
    }

    @PostMapping(value = {"/queue/file", "/queue/file/**"})
    public ResponseEntity<String> uploadFileToQueue(HttpServletRequest request, @RequestParam("file") MultipartFile file) throws Exception {
        String queueName = getDestinationName(request, solaceService.getDefaultQueue());
        logger.info("接收到上傳檔案 '{}' 到 Queue '{}' 的請求。", file.getOriginalFilename(), queueName);
        solaceService.sendFile(queueName, SolaceService.DestinationType.QUEUE, file.getOriginalFilename(), file.getBytes());
        String responseMessage = "檔案已成功發送到 Queue: " + queueName;
        logger.info("成功處理上傳檔案到 Queue '{}' 的請求。", queueName);
        return ResponseEntity.ok(responseMessage);
    }

    @PostMapping(value = {"/subscribe/topic", "/subscribe/topic/**"})
    public ResponseEntity<String> subscribeToTopic(HttpServletRequest request) throws Exception {
        String topicName = getDestinationName(request, solaceService.getDefaultTopic());
        logger.info("接收到訂閱 Topic '{}' 的請求。", topicName);
        solaceService.subscribeToTopic(topicName);
        String responseMessage = "已成功訂閱 Topic: " + topicName;
        logger.info("成功處理訂閱 Topic '{}' 的請求。", topicName);
        return ResponseEntity.ok(responseMessage);
    }

    @PostMapping(value = {"/listen/queue", "/listen/queue/**"})
    public ResponseEntity<String> listenToQueue(HttpServletRequest request) throws Exception {
        String queueName = getDestinationName(request, solaceService.getDefaultQueue());
        logger.info("接收到監聽 Queue '{}' 的請求。", queueName);
        solaceService.receiveFromQueue(queueName);
        String responseMessage = "已開始監聽 Queue: " + queueName;
        logger.info("成功處理監聽 Queue '{}' 的請求。", queueName);
        return ResponseEntity.ok(responseMessage);
    }

    @GetMapping(value = {"/messages/queue", "/messages/queue/**"})
    public ResponseEntity<List<String>> getQueueMessages(HttpServletRequest request) {
        String queueName = getDestinationName(request, solaceService.getDefaultQueue());
        logger.info("接收到獲取 Queue '{}' 訊息的請求。", queueName);
        List<String> messages = solaceService.getAndClearQueueMessages(queueName);
        logger.info("成功獲取到 {} 條來自 Queue '{}' 的訊息。", messages.size(), queueName);
        return ResponseEntity.ok(messages);
    }

    @GetMapping(value = {"/messages/topic", "/messages/topic/**"})
    public ResponseEntity<List<String>> getTopicMessages(HttpServletRequest request) {
        String topicName = getDestinationName(request, solaceService.getDefaultTopic());
        logger.info("接收到獲取 Topic '{}' 訊息的請求。", topicName);
        List<String> messages = solaceService.getAndClearTopicMessages(topicName);
        logger.info("成功獲取到 {} 條來自 Topic '{}' 的訊息。", messages.size(), topicName);
        return ResponseEntity.ok(messages);
    }
}
