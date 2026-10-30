package photography.photography.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import photography.photography.api.ApiException;

/**
 * Image uploads on local disk. The type is decided from the file's magic bytes (never from the client-supplied name or content type),
 * stored under a random name, and served back with a fixed content type and nosniff. Replace with object storage for multi-server setups.
 */
@Service
public class UploadService {
    private final Path dir;
    private final long maxBytes;
    private final String baseUrl;

    public UploadService(@Value("${app.upload.dir:./uploads}") String dir, @Value("${app.upload.max-bytes:8388608}") long maxBytes,
                         @Value("${app.public-base-url:http://localhost:8080}") String baseUrl) {
        this.dir = Paths.get(dir).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.validation("Choose an image to upload.");
        }
        if (file.getSize() > maxBytes) {
            throw ApiException.validation("The image is larger than " + (maxBytes / 1024 / 1024) + " MB.");
        }
        try {
            byte[] head;
            try (InputStream in = file.getInputStream()) {
                head = in.readNBytes(12);
            }
            String ext = extensionFor(head);
            if (ext == null) {
                throw ApiException.validation("Only JPG, PNG or WebP images are allowed.");
            }
            Files.createDirectories(dir);
            String name = UUID.randomUUID() + "." + ext;
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
            return baseUrl + "/uploads/" + name;
        } catch (IOException e) {
            throw new ApiException(500, "SERVER", "Could not store the image.", null);
        }
    }

    /** jpg, png or webp by signature; null for anything else. */
    static String extensionFor(byte[] h) {
        if (h.length >= 3 && (h[0] & 0xff) == 0xFF && (h[1] & 0xff) == 0xD8 && (h[2] & 0xff) == 0xFF) {
            return "jpg";
        }
        if (h.length >= 8 && (h[0] & 0xff) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G' && h[4] == 0x0D && h[5] == 0x0A && h[6] == 0x1A && h[7] == 0x0A) {
            return "png";
        }
        if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return "webp";
        }
        return null;
    }

    /** Resolves a stored file; the name must be exactly what store() generates, which rules out path traversal. */
    public Path resolve(String name) {
        if (name == null || !name.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp)$")) {
            throw ApiException.notFound("Not found");
        }
        Path p = dir.resolve(name).normalize();
        if (!p.startsWith(dir) || !Files.isRegularFile(p)) {
            throw ApiException.notFound("Not found");
        }
        return p;
    }

    public static String contentType(String name) {
        return name.endsWith(".png") ? "image/png" : name.endsWith(".webp") ? "image/webp" : "image/jpeg";
    }
}
