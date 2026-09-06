package com.intelliassist.backend.service;

import com.intelliassist.backend.exception.ApiException;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

/**
 * Validates an upload before it is forwarded to the AI service.
 *
 * <p>The AI service validates again — it sniffs the content type rather than
 * trusting the declared one — so this is not the security boundary. It exists
 * to reject an obviously bad upload without spending 50 MB of bandwidth and a
 * round trip to find out.
 *
 * <p>This backend deliberately does <em>not</em> parse documents. Extraction is
 * the AI service's job, and duplicating PyMuPDF here would mean two parsers
 * that can disagree about what a file contains.
 */
public final class FileUploadValidator {

    /** Matches the AI service's own ceiling; a larger value here helps nobody. */
    public static final long MAX_SIZE_BYTES = 50L * 1024 * 1024;

    private static final Map<String, String> EXTENSION_TO_TYPE =
            Map.of(
                    "pdf", "application/pdf",
                    "docx",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "txt", "text/plain",
                    "md", "text/markdown");

    private FileUploadValidator() {}

    /** Thrown for anything a client can fix by uploading a different file. */
    public static class InvalidUploadException extends ApiException {
        InvalidUploadException(HttpStatus status, String code, String message) {
            super(status, code, message);
        }
    }

    /**
     * Checks the file and returns the content type to send onward.
     *
     * @return the resolved content type, derived from the extension rather than
     *     from the browser's guess
     */
    public static String validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidUploadException(
                    HttpStatus.BAD_REQUEST, "EMPTY_FILE", "The uploaded file is empty");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new InvalidUploadException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "PAYLOAD_TOO_LARGE",
                    "File exceeds the %d MB limit".formatted(MAX_SIZE_BYTES / (1024 * 1024)));
        }

        String filename = sanitizeFilename(file.getOriginalFilename());
        String extension = extensionOf(filename);

        String resolvedType = EXTENSION_TO_TYPE.get(extension);
        if (resolvedType == null) {
            throw new InvalidUploadException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "UNSUPPORTED_DOCUMENT_TYPE",
                    "Supported file types are PDF, DOCX, TXT and MD");
        }
        return resolvedType;
    }

    /**
     * Strips any path from a client-supplied filename.
     *
     * <p>A browser sends only a name, but a scripted client can send
     * {@code ../../etc/passwd}. Nothing here writes to disk, so this is not
     * currently exploitable — it is stripped anyway, because the value is
     * stored, logged and displayed, and the next person to add file storage
     * should not have to rediscover this.
     */
    public static String sanitizeFilename(String rawFilename) {
        if (rawFilename == null || rawFilename.isBlank()) {
            return "upload";
        }

        // Handles both separators: a Windows client sends backslashes, which
        // Paths on Linux would not treat as a separator at all.
        String name = rawFilename.replace('\\', '/');
        name = Paths.get(name).getFileName().toString();
        name = name.replaceAll("[\\p{Cntrl}]", "").strip();

        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return "upload";
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }


    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 || dot == filename.length() - 1
                ? ""
                : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
