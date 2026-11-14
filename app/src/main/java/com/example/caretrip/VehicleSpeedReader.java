package com.example.caretrip;

import android.car.Car;
import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * Read-only Android Automotive speed source for CareTrip.
 * The incoming PERF_VEHICLE_SPEED is m/s; display uses km/h.
 */
public final class VehicleSpeedReader {

    public interface Listener {
        void onSpeed(float speedKmh);
        void onStatus(String message);
    }

    private final Context context;
    private final Listener listener;
    private Car car;
    private CarPropertyManager propertyManager;
    private boolean subscribed;
    private boolean active;

    private final CarPropertyManager.CarPropertyEventCallback callback =
            new CarPropertyManager.CarPropertyEventCallback() {
                @Override
                public void onChangeEvent(CarPropertyValue value) {
                    if (!active || value.getPropertyId() != VehiclePropertyIds.PERF_VEHICLE_SPEED) {
                        return;
                    }
                    if (value.getStatus() != CarPropertyValue.STATUS_AVAILABLE) {
                        listener.onStatus("차량 속도 신호 사용 불가");
                        return;
                    }
                    Object raw = value.getValue();
                    if (!(raw instanceof Number)) {
                        listener.onStatus("차량 속도 데이터 형식 오류");
                        return;
                    }
                    float metersPerSecond = ((Number) raw).floatValue();
                    if (!Float.isFinite(metersPerSecond)) {
                        listener.onStatus("차량 속도 데이터 유효하지 않음");
                        return;
                    }
                    listener.onSpeed(Math.abs(metersPerSecond) * 3.6f);
                }

                @Override
                public void onErrorEvent(int propertyId, int areaId) {
                    if (active && propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) {
                        listener.onStatus("차량 속도 읽기 오류");
                    }
                }
            };

    public VehicleSpeedReader(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        if (active) return;
        active = true;
        try {
            // This non-blocking factory avoids waiting on the UI thread.
            car = Car.createCar(
                    context,
                    new Handler(Looper.getMainLooper()),
                    Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
                    (connectedCar, ready) -> {
                        if (!active) return;
                        if (!ready) {
                            unsubscribe();
                            listener.onStatus("차량 서비스 연결 대기 중");
                            return;
                        }
                        subscribe(connectedCar);
                    });
            if (car == null) {
                listener.onStatus("차량 서비스를 찾을 수 없음");
            }
        } catch (SecurityException ex) {
            listener.onStatus("차량 속도 접근 권한이 없음");
        } catch (RuntimeException ex) {
            listener.onStatus("차량 서비스 연결 실패");
        }
    }

    private void subscribe(Car connectedCar) {
        try {
            unsubscribe();
            propertyManager = (CarPropertyManager) connectedCar.getCarManager(Car.PROPERTY_SERVICE);
            if (propertyManager == null ||
                    propertyManager.getCarPropertyConfig(VehiclePropertyIds.PERF_VEHICLE_SPEED) == null) {
                listener.onStatus("차량 속도 미지원 또는 권한 없음");
                return;
            }
            subscribed = propertyManager.registerCallback(
                    callback,
                    VehiclePropertyIds.PERF_VEHICLE_SPEED,
                    CarPropertyManager.SENSOR_RATE_UI);
            listener.onStatus(subscribed ? "차량 속도 신호 대기 중" : "속도 이벤트 구독 실패");
        } catch (SecurityException ex) {
            listener.onStatus("차량 속도 읽기 권한 거부");
        } catch (RuntimeException ex) {
            listener.onStatus("차량 속도 속성 사용 불가");
        }
    }

    private void unsubscribe() {
        if (propertyManager != null && subscribed) {
            try {
                propertyManager.unregisterCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED);
            } catch (RuntimeException ignored) {
                // The car service may have disconnected.
            }
        }
        propertyManager = null;
        subscribed = false;
    }

    public void stop() {
        active = false;
        unsubscribe();
        if (car != null) {
            car.disconnect();
            car = null;
        }
    }
}
