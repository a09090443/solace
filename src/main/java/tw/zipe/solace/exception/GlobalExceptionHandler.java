package tw.zipe.solace.exception;

import com.solacesystems.jcsmp.JCSMPException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;

import java.io.IOException;
import java.util.Map;

/**
 * 全域例外處理器。
 * <p>
 * 使用 {@code @ControllerAdvice} 來攔截並處理整個應用程式中拋出的特定例外。
 * 為 JCSMP、I/O 和其他通用例外提供統一的錯誤回應格式。
 * </p>
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String ERROR_KEY = "error";

    /**
     * 處理 Solace JCSMP 相關的例外。
     *
     * @param ex      攔截到的 {@link JCSMPException}。
     * @param request 當前的 Web 請求。
     * @return 一個包含錯誤訊息的 {@link ResponseEntity}，狀態碼為 500 (Internal Server Error)。
     */
    @ExceptionHandler(JCSMPException.class)
    public ResponseEntity<Object> handleJCSMPException(JCSMPException ex, WebRequest request) {
        logger.error("Solace JCSMP Error: {}", ex.getMessage(), ex);
        Map<String, String> body = Map.of(
                ERROR_KEY, "Solace Messaging Error",
                "message", ex.getMessage()
        );
        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * 處理檔案 I/O 相關的例外。
     *
     * @param ex      攔截到的 {@link IOException}。
     * @param request 當前的 Web 請求。
     * @return 一個包含錯誤訊息的 {@link ResponseEntity}，狀態碼為 400 (Bad Request)。
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Object> handleIOException(IOException ex, WebRequest request) {
        logger.error("IO Error: {}", ex.getMessage(), ex);
        Map<String, String> body = Map.of(
                ERROR_KEY, "File I/O Error",
                "message", "Error processing file."
        );
        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    /**
     * 處理所有其他未被捕獲的例外。
     *
     * @param ex      攔截到的 {@link Exception}。
     * @param request 當前的 Web 請求。
     * @return 一個包含通用錯誤訊息的 {@link ResponseEntity}，狀態碼為 500 (Internal Server Error)。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleGlobalException(Exception ex, WebRequest request) {
        logger.error("Unhandled Error: {}", ex.getMessage(), ex);
        Map<String, String> body = Map.of(
                ERROR_KEY, "Internal Server Error",
                "message", "An unexpected error occurred."
        );
        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
