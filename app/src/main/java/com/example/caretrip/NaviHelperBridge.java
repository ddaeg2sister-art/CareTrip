package com.example.caretrip;

import android.app.Activity;

import ai.pleos.playground.navi.constants.NaviErrorCode;
import ai.pleos.playground.navi.data.DestinationInfo;
import ai.pleos.playground.navi.helper.NaviHelper;
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener;
import kotlin.Unit;

/**
 * Pleos NaviHelper v3.0.5 연결 및 현재 목적지 확인.
 * 장소 검색/경유지 자동 추가는 구현하지 않습니다.
 */
public class NaviHelperBridge {
    public interface StatusListener {
        void onStatus(String message);
    }

    private final Activity activity;
    private final StatusListener listener;
    private NaviHelper helper;
    private boolean connected = false;
    private boolean connecting = false;
    private boolean released = false;

    private final NaviHelperEventListener naviListener = new NaviHelperEventListener() {
        @Override
        public void onDestinationInfo(DestinationInfo info) {
            postStatus(info == null
                    ? "내비게이션 연결됨 · 설정된 목적지가 없습니다."
                    : "내비게이션에서 받은 현재 목적지: " + info);
        }

        @Override
        public void onError(NaviErrorCode errorCode) {
            postStatus("내비게이션 요청 오류: " + errorCode);
        }
    };

    public NaviHelperBridge(Activity activity, StatusListener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    public void checkDestination() {
        if (released) return;
        if (connected) {
            requestDestination();
            return;
        }
        if (connecting) {
            postStatus("내비게이션 서비스에 연결하는 중입니다.");
            return;
        }

        connecting = true;
        postStatus("NaviHelper 초기 연결 요청 중...");
        try {
            helper = new NaviHelper(activity.getApplicationContext());
            helper.initialize(
                    () -> {
                        if (released) return Unit.INSTANCE;
                        connecting = false;
                        connected = true;
                        try {
                            helper.addListener(naviListener);
                            postStatus("내비게이션 연결 성공 · 목적지 정보를 조회합니다.");
                            requestDestination();
                        } catch (Exception e) {
                            postStatus("목적지 조회 준비 실패: " + e.getMessage());
                        }
                        return Unit.INSTANCE;
                    },
                    error -> {
                        connecting = false;
                        postStatus("내비게이션 서비스 연결 실패: " + error.getMessage()
                                + " (기본 Navigation 앱이 설치·설정됐는지 확인)");
                        return Unit.INSTANCE;
                    }
            );
        } catch (Exception e) {
            connecting = false;
            postStatus("NaviHelper 초기화 실패: " + e.getMessage());
        }
    }

    private void requestDestination() {
        try {
            if (helper != null) {
                postStatus("현재 목적지 정보 조회 요청을 보냈습니다. Navigation 앱의 응답을 기다립니다.");
                helper.getDestinationInfo();
            }
        } catch (Exception e) {
            postStatus("목적지 조회 요청 실패: " + e.getMessage());
        }
    }

    private void postStatus(String message) {
        activity.runOnUiThread(() -> {
            if (!released) listener.onStatus(message);
        });
    }

    public void release() {
        released = true;
        if (helper != null) {
            try {
                if (connected) helper.removeListener(naviListener);
                helper.release();
            } catch (Exception ignored) {
                // 이미 서비스가 종료된 경우 무시
            }
            helper = null;
        }
        connected = false;
        connecting = false;
    }
}
