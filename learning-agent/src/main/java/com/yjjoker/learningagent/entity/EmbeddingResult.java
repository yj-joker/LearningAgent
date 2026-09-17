package com.yjjoker.learningagent.entity;

import java.util.List;

//Aliyun返回的 Embedding 结果。
public record EmbeddingResult(int index, List<Double> embedding) {

    //紧凑构造器，替换自动生成的构造器
    public EmbeddingResult {
        embedding = List.copyOf(embedding);
    }
}
