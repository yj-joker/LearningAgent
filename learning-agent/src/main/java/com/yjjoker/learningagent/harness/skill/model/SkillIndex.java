package com.yjjoker.learningagent.harness.skill.model;

import lombok.Getter;

import java.util.regex.Pattern;

// 技能的轻量索引；只有名称、用途和版本，不包含完整说明或文件路径。
@Getter
public final class SkillIndex {
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern VERSION_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}");

    private final String name;
    private final String description;
    private final String version;

    // 构造后不能再修改，避免索引与注册时的正文版本不一致。
    public SkillIndex(String name, String description, String version) {
        if (!isValidName(name)) {
            throw new IllegalArgumentException("name 只能包含小写字母、数字和单个连接符，长度为 1 到 64");
        }
        if (description == null || description.isBlank() || description.length() > 1024) {
            throw new IllegalArgumentException("description 必须是 1 到 1024 个字符的非空说明");
        }
        // 版本显式写在 metadata.version 中；本阶段不比较版本大小，也不做热更新。
        if (version == null || !VERSION_PATTERN.matcher(version).matches()) {
            throw new IllegalArgumentException("metadata.version 必须是 1 到 64 个字符的版本标识");
        }
        this.name = name;
        this.description = description.strip();
        this.version = version;
    }

    // 名称用于查注册表，不是文件路径；读取端与注册端使用相同规则。
    public static boolean isValidName(String name) {
        return name != null && name.length() <= 64 && NAME_PATTERN.matcher(name).matches();
    }
}
