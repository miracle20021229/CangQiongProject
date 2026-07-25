package com.sky.utils;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 高德地图map转换
 */
@Component
public class MapUtil {

    @Value("${sky.map.key}")
    private String key;

    @Value("${sky.map.adminAddress}")
    private String adminAddress;

    private static final String GEOCODE_URL = "https://restapi.amap.com/v3/geocode/geo";
    private static final String RIDING_URL = "https://restapi.amap.com/v4/direction/bicycling";

    public String getLocation(String address) {
        Map<String, String> paraMap = new HashMap<>();
        paraMap.put("key", key);
        paraMap.put("address", address);

        String json = HttpClientUtil.doGet(GEOCODE_URL, paraMap);
        JSONObject jsonObject = JSONObject.parseObject(json);
        JSONArray geocodes = jsonObject.getJSONArray("geocodes");
        if (geocodes == null || geocodes.isEmpty()) {
            return null;
        }

        return geocodes.getJSONObject(0).getString("location");
    }

    public Long getRidingDistance(String destination) {
        String origin = getLocation(adminAddress);
        if (origin == null || destination == null) {
            return null;
        }

        Map<String, String> paraMap = new HashMap<>();
        paraMap.put("key", key);
        paraMap.put("origin", origin);
        paraMap.put("destination", destination);

        String json = HttpClientUtil.doGet(RIDING_URL, paraMap);
        JSONObject jsonObject = JSONObject.parseObject(json);
        JSONObject data = jsonObject.getJSONObject("data");
        if (data == null) {
            return null;
        }

        JSONArray paths = data.getJSONArray("paths");
        if (paths == null || paths.isEmpty()) {
            return null;
        }

        return paths.getJSONObject(0).getLong("distance");
    }
}
