package com.yjjoker.learningagent.page;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/** 通用分页请求。页码从 1 开始，未传参数时默认查询第一页的 20 条数据。 */
@Data
public  class PageRequest {

    @Min(value = 1, message = "页码不能小于 1")
    private int page = 1;
    @Min(value = 1, message = "每页数量不能小于 1")
    @Max(value = 100, message = "每页数量不能超过 100")
    private int size = 20;
    public long offset() {
        return (long) (page - 1) * size;
    }
}
