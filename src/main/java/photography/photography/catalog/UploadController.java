package photography.photography.catalog;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class UploadController {
    private final UploadService uploads;

    public UploadController(UploadService uploads) {
        this.uploads = uploads;
    }

    @PostMapping("/uploads")
    public Map<String, String> upload(@RequestParam("file") MultipartFile file) {
        return Map.of("url", uploads.store(file));
    }

    /** Profile pictures: any signed-in account (customers too) may upload one. Same content checks and size limit as /uploads. */
    @PostMapping("/uploads/avatar")
    public Map<String, String> uploadAvatar(@RequestParam("file") MultipartFile file) {
        return Map.of("url", uploads.store(file));
    }

    @GetMapping("/uploads/{name}")
    public ResponseEntity<Resource> get(@PathVariable String name) {
        Resource r = new FileSystemResource(uploads.resolve(name));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(UploadService.contentType(name)))
                .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff").header("Content-Security-Policy", "default-src 'none'")
                .header("Cross-Origin-Resource-Policy", "cross-origin").body(r);
    }
}
