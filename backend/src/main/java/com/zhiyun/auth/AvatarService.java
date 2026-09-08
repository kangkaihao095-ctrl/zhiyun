package com.zhiyun.auth;

import com.zhiyun.common.ApiException;
import com.zhiyun.config.ZhiyunProperties;
import com.zhiyun.domain.AppUser;
import com.zhiyun.repo.UserRepo;
import com.zhiyun.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AvatarService {
    private static final Logger log = LoggerFactory.getLogger(AvatarService.class);
    private static final long MAX_BYTES = 2 * 1024 * 1024;
    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "webp");

    private final UserRepo userRepo;
    private final Path avatarDir;

    public AvatarService(UserRepo userRepo, ZhiyunProperties properties) {
        this.userRepo = userRepo;
        this.avatarDir = Path.of(properties.getStorage().getAvatarDir()).toAbsolutePath().normalize();
    }

    public String publicUrl(AppUser user) {
        if (user == null || user.getAvatarPath() == null || user.getAvatarPath().isBlank()) {
            return null;
        }
        Path file = resolveExisting(user);
        return file == null ? null : "/api/me/avatar";
    }

    public Resource loadMine() {
        AppUser user = userRepo.findById(TenantContext.userId()).orElseThrow(() -> ApiException.notFound("用户不存在"));
        Path file = resolveExisting(user);
        if (file == null) {
            throw ApiException.notFound("还没有头像");
        }
        return new FileSystemResource(file);
    }

    public MediaType mediaType(Resource resource) {
        String name = resource.getFilename() == null ? "" : resource.getFilename().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (name.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        return MediaType.IMAGE_JPEG;
    }

    @Transactional
    public Map<String, Object> upload(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw ApiException.bad("请选择头像文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.bad("头像不能超过 2MB");
        }
        String ext = extension(file);
        if (!ALLOWED_EXT.contains(ext)) {
            throw ApiException.bad("只支持 jpg、png、webp");
        }
        byte[] bytes = file.getBytes();
        if (!magicOk(bytes, ext)) {
            throw ApiException.bad("文件内容不是可用的图片");
        }
        AppUser user = userRepo.findById(TenantContext.userId()).orElseThrow(() -> ApiException.notFound("用户不存在"));
        Path dest = isolatedPath(user.getTenantId(), user.getId(), ext);
        Files.createDirectories(dest.getParent());
        Files.write(dest, bytes);
        user.setAvatarPath(relativePath(user.getTenantId(), user.getId(), ext));
        userRepo.save(user);
        return Map.of("avatarUrl", "/api/me/avatar");
    }

    @Transactional
    public void seedDemo(AppUser user) {
        if (user == null) {
            return;
        }
        Path existing = resolveExisting(user);
        if (existing != null) {
            return;
        }
        try {
            ClassPathResource resource = new ClassPathResource("demo/avatar.png");
            if (!resource.exists()) {
                return;
            }
            Path dest = isolatedPath(user.getTenantId(), user.getId(), "png");
            Files.createDirectories(dest.getParent());
            try (InputStream in = resource.getInputStream()) {
                Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            user.setAvatarPath(relativePath(user.getTenantId(), user.getId(), "png"));
            userRepo.save(user);
        } catch (Exception e) {
            log.warn("demo avatar seed skipped: {}", e.getMessage());
        }
    }

    private Path resolveExisting(AppUser user) {
        String rel = user.getAvatarPath();
        if (rel == null || rel.isBlank()) {
            return null;
        }
        Path file = avatarDir.resolve(rel).normalize();
        if (!file.startsWith(avatarDir) || !Files.isRegularFile(file)) {
            return null;
        }
        return file;
    }

    private Path isolatedPath(long tenantId, long userId, String ext) {
        return avatarDir.resolve(relativePath(tenantId, userId, ext));
    }

    private static String relativePath(long tenantId, long userId, String ext) {
        return tenantId + "/" + userId + "." + ext;
    }

    private static String extension(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        String ct = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png") || ct.contains("png")) {
            return "png";
        }
        if (name.endsWith(".webp") || ct.contains("webp")) {
            return "webp";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg") || ct.contains("jpeg")) {
            return "jpg";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    private static boolean magicOk(byte[] bytes, String ext) {
        if (bytes == null || bytes.length < 12) {
            return false;
        }
        if ("png".equals(ext)) {
            return bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47;
        }
        if ("jpg".equals(ext) || "jpeg".equals(ext)) {
            return bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8;
        }
        if ("webp".equals(ext)) {
            return bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
        }
        return false;
    }
}
