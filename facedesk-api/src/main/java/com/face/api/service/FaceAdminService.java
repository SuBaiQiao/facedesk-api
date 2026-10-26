package com.face.api.service;

import com.face.api.model.*;
import com.face.api.repository.FaceRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 分页管理不在列表传输整个人脸图库，原图通过独立接口按需读取。
 */
@Service
public class FaceAdminService {
    private final FaceRepository repository;

    public FaceAdminService(FaceRepository repository) {
        this.repository = repository;
    }

    // 列表只返回轻量元数据；主图、参考图片及详细信息由独立接口按需加载。
    public Map<String, Object> page(String role, String query, int page, int size) {
        if (!FaceRoles.isSupported(role)) {
            throw new IllegalArgumentException("不受支持的角色");
        }
        if (page < 1 || size < 1 || size > 50) {
            throw new IllegalArgumentException("页码需大于 0，每页数量为 1～50");
        }
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<FaceRecord> all = repository.findAll();
        List<FaceRecord> matches = all.stream()
                .filter(r -> role == null || role.isEmpty() || role.equals(r.getRole()))
                .filter(r -> needle.isEmpty() || r.getCode().toLowerCase(Locale.ROOT).contains(needle)
                        || (r.getName() != null && r.getName().toLowerCase(Locale.ROOT).contains(needle)))
                .sorted(Comparator.comparing(FaceRecord::getCode).thenComparing(FaceRecord::getRole)).collect(Collectors.toList());
        int pages = Math.max(1, (matches.size() + size - 1) / size);
        int current = Math.min(page, pages), start = (current - 1) * size;
        List<Map<String, Object>> items = new ArrayList<>();
        for (FaceRecord record : matches.subList(start, Math.min(start + size, matches.size()))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", record.getRole());
            item.put("code", record.getCode());
            item.put("name", record.getName());
            item.put("hasImage", record.getImageBase64() != null && !record.getImageBase64().isEmpty());
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("page", current);
        result.put("pageSize", size);
        result.put("total", matches.size());
        result.put("pages", pages);
        result.put("databaseTotal", all.size());
        return result;
    }

    public FaceRecord get(String role, String code) {
        if (role == null || role.isEmpty() || !FaceRoles.isSupported(role) || code == null || code.isEmpty()) {
            throw new IllegalArgumentException("请指定有效的角色和编号");
        }
        return repository.find(role, code).orElseThrow(() -> new IllegalArgumentException("人员记录不存在，请刷新列表"));
    }

    public void updateName(String role, String code, String name) {
        get(role, code);
        if (name != null && name.length() > 128) {
            throw new IllegalArgumentException("姓名不能超过 128 个字符");
        }
        repository.updateName(role, code, name);
    }

    public Map<String, Object> detail(String role, String code) {
        get(role, code);
        return repository.detail(role, code);
    }

    public String referenceImage(String role, String code, String id) {
        get(role, code);
        return repository.referenceImage(role, code, id);
    }

    public void removeReference(String role, String code, String id) {
        get(role, code);
        repository.removeReference(role, code, id);
    }
}
