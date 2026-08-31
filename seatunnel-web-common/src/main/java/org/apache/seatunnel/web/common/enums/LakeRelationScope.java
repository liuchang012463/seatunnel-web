package org.apache.seatunnel.web.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** Scope represented by a task relation. */
@AllArgsConstructor
@Getter
public enum LakeRelationScope {
    TABLE("TABLE"),
    NAMESPACE("NAMESPACE");

    @EnumValue
    private final String code;
}
