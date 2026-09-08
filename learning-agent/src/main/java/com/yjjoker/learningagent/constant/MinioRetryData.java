package com.yjjoker.learningagent.constant;

import java.util.Set;
// MinIO 需要重试的错误数据
public class MinioRetryData {
    public static final Integer MINIO_MAX_RETRY=3;
    /**
     * 这些 HTTP 状态通常表示临时性故障。
     *  429 和 503，通常应该配合退避等待。
     */
    public static final Set<Integer> RETRYABLE_HTTP_STATUS = Set.of(
            408, // Request Timeout：请求处理超时
            429, // Too Many Requests：请求过多，被限流
            500, // Internal Server Error：服务端内部临时错误
            502, // Bad Gateway：网关没有拿到后端正常响应
            503, // Service Unavailable：服务暂时不可用或过载
            504  // Gateway Timeout：网关等待后端响应超时
    );
    /**
     * 这些是常见的临时性 S3/MinIO 错误码。
     * 不同的 S3 兼容服务在错误码命名上可能略有差异，
     * 因此同时保留了 MinIO、AWS S3 或其他兼容服务中常见的名称。
     */
    public static final Set<String> RETRYABLE_S3_CODES = Set.of(
            "RequestTimeout",
            // 服务端处理请求超时，稍后重试可能成功
            "RequestTimeoutException",
            // 某些兼容 S3 的服务使用这个异常名称
            "InternalError",
            // 服务端内部错误，通常属于暂时性故障
            "ServiceUnavailable",
            // 服务暂时不可用
            "SlowDown",
            // 服务端要求降低请求速度，通常表示限流或负载过高
            "TooManyRequests",
            // 请求数量过多
            "Throttling",
            // 请求被限流
            "ThrottlingException"
            // 某些兼容 S3 的服务使用的限流异常名称
    );
    /**
     * 这些错误通常需要修改配置、权限、请求参数或文件本身。
     * 仅仅等待后重新发送相同请求，通常仍然会失败。
     */
    public static final Set<String> NON_RETRYABLE_S3_CODES = Set.of(
            "AccessDenied",
            // 当前账号没有访问或上传权限
            "InvalidAccessKeyId",
            // Access Key 不存在、写错或已经失效
            "SignatureDoesNotMatch",
            // Secret Key 错误、签名计算错误，
            // 或者请求被代理修改导致签名不匹配
            "NoSuchBucket",
            // Bucket 不存在。
            // 应该先创建 Bucket 或检查 Bucket 名称，
            // 而不是原样重复上传
            "NoSuchKey",
            // 对象不存在。
            // 主要出现在下载、删除或查询对象时
            "InvalidBucketName",
            // Bucket 名称不合法
            "InvalidArgument",
            // 请求参数不合法
            "InvalidRequest",
            // 请求格式或操作不符合服务端要求
            "EntityTooLarge",
            // 文件或请求体超过服务端允许的大小
            "EntityTooSmall",
            // 某些分片上传场景下，分片大小不符合要求
            "BadDigest",
            // 服务端计算的数据摘要与客户端提供的摘要不一致，
            // 说明数据完整性可能存在问题
            "InvalidDigest",
            // 提供的数据摘要不正确
            "RequestTimeTooSkewed",
            // 客户端服务器与 MinIO 服务器之间的时间偏差过大。
            // 应该同步系统时间或配置 NTP
            "MethodNotAllowed",
            // 当前 HTTP 方法不允许执行该操作
            "NotImplemented"
            // MinIO 或兼容服务不支持当前操作
    );
}
