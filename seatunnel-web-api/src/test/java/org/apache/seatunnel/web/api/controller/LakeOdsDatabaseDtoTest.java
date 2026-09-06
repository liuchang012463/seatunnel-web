package org.apache.seatunnel.web.api.controller;

import jakarta.validation.constraints.NotBlank;
import org.apache.seatunnel.web.spi.bean.dto.LakeOdsDatabaseCreateDTO;
import org.apache.seatunnel.web.spi.bean.vo.LakeOdsDatabaseVO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LakeOdsDatabaseDtoTest {

    @Test
    void createDtoRequiresOnlyTheUserControlledCustomName() throws NoSuchFieldException {
        Field customName = LakeOdsDatabaseCreateDTO.class.getDeclaredField("customName");
        assertNotNull(customName.getAnnotation(NotBlank.class));
        assertTrue(Arrays.stream(LakeOdsDatabaseCreateDTO.class.getDeclaredFields())
                .noneMatch(field -> field.getName().toLowerCase().contains("password")
                        || field.getName().toLowerCase().contains("connection")));
    }

    @Test
    void databaseVoDoesNotExposeConnectionOrOperationSecrets() {
        assertTrue(Arrays.stream(LakeOdsDatabaseVO.class.getDeclaredFields())
                .noneMatch(field -> {
                    String name = field.getName().toLowerCase();
                    return name.contains("password") || name.contains("connection")
                            || name.contains("operationtoken");
                }));
    }
}
