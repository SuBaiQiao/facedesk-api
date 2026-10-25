package com.face.api;

import com.face.api.model.FaceRecord;
import com.face.api.repository.FaceRepository;
import com.face.api.service.FaceAdminService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FaceAdminServiceTest {
    @Test @SuppressWarnings("unchecked") void filtersPaginatesAndDoesNotSendPhotosOrFeaturesInList() {
        FaceRepository repo = mock(FaceRepository.class);
        when(repo.findAll()).thenReturn(Arrays.asList(
            new FaceRecord("Guest", "002", "王小明", "PRIVATE_IMAGE", new float[128]),
            new FaceRecord("Family", "001", "李明", "PRIVATE_IMAGE", new float[128]),
            new FaceRecord("Guest", "003", "张三", null, new float[128])));
        FaceAdminService service = new FaceAdminService(repo);
        Map<String, Object> result = service.page("Guest", "", 1, 1);
        assertEquals(2, result.get("total")); assertEquals(3, result.get("databaseTotal"));
        Map<String, Object> person = ((List<Map<String, Object>>) result.get("items")).get(0);
        assertEquals("002", person.get("code")); assertEquals(true, person.get("hasImage"));
        assertFalse(person.containsKey("feature")); assertFalse(person.containsKey("imageBase64"));
        assertEquals(1, service.page("Guest", "小明", 1, 12).get("total"));
        assertEquals(1, service.page("", "001", 1, 12).get("total"));
        assertEquals(2, service.page("Guest", "", 999, 1).get("page"));
        assertThrows(IllegalArgumentException.class, () -> service.page("unknown", "", 1, 12));
        assertThrows(IllegalArgumentException.class, () -> service.page("", "", 0, 12));
    }
}
