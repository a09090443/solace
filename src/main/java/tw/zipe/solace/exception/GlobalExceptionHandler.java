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

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String ERROR_KEY = "error";

    @ExceptionHandler(JCSMPException.class)
    public ResponseEntity<Object> handleJCSMPException(JCSMPException ex, WebRequest request) {
        logger.error("Solace JCSMP Error: {}", ex.getMessage(), ex);
        Map<String, String> body = Map.of(
                ERROR_KEY, "Solace Messaging Error",
                "message", ex.getMessage()
        );
        return new ResponseEntity<>(body, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Object> handleIOException(IOException ex, WebRequest request) {
        logger.error("IO Error: {}", ex.getMessage(), ex);
        Map<String, String> body = Map.of(
                ERROR_KEY, "File I/O Error",
                "message", "Error processing file."
        );
        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

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
