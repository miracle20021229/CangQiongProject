package com.sky.utils;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.util.Date;


/**
 * 阿里云
 */
@Data
@AllArgsConstructor
@Slf4j
public class AliOssUtil {

    private String endpoint;
    private String accessKeyId;
    private String accessKeySecret;
    private String bucketName;

    public String upload(byte[] bytes, String objectName) {
        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        String filePath;

        try {
            ossClient.putObject(bucketName, objectName, new ByteArrayInputStream(bytes));
            Date expiration = new Date(System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365 * 10);
            filePath = ossClient.generatePresignedUrl(bucketName, objectName, expiration).toString();
        } catch (OSSException oe) {
            log.error("OSS upload failed, errorCode={}, requestId={}, hostId={}",
                    oe.getErrorCode(), oe.getRequestId(), oe.getHostId(), oe);
            throw new RuntimeException(oe);
        } catch (ClientException ce) {
            log.error("OSS client failed", ce);
            throw new RuntimeException(ce);
        } finally {
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }

        log.info("file uploaded to {}", filePath);
        return filePath;
    }
}
