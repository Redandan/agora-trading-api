package com.agora.util;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class GeoUtils {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final double DEGREE_TO_KM = 111.0; // 1度約等於111公里

    /**
     * 創建地理點
     */
    public static Point createPoint(double longitude, double latitude) {
        return GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude));
    }

    /**
     * 計算兩點之間的距離（公里）
     */
    public static double calculateDistance(Point point1, Point point2) {
        double distanceInDegrees = point1.distance(point2);
        return distanceInDegrees * DEGREE_TO_KM;
    }

    /**
     * 計算實際駕駛距離（考慮駕駛係數）
     */
    public static double calculateDrivingDistance(Point point1, Point point2, double drivingFactor) {
        return calculateDistance(point1, point2) * drivingFactor;
    }

    /**
     * 計算預計送達時間（分鐘）
     */
    public static int calculateDeliveryTime(double distance, double averageSpeed) {
        return (int) Math.ceil((distance / averageSpeed) * 60);
    }

    /**
     * 計算配送費用
     */
    public static BigDecimal calculateDeliveryFee(double distance, double baseFee, double feePerKm, double minFee) {
        double fee = baseFee + (distance * feePerKm);
        return BigDecimal.valueOf(Math.max(fee, minFee));
    }

    /**
     * 檢查點是否在指定半徑範圍內
     */
    public static boolean isWithinRadius(Point center, Point point, double radius) {
        return calculateDistance(center, point) <= radius;
    }
} 