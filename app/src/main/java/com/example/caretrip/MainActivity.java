package com.example.caretrip;

import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.RadioGroup;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private TextView tvTimer, tvSinceRest, tvTarget, tvStatus, tvRestStatus;
    private TextView tvRestCount, tvGuide, tvHistory, tvInterval;
    private TextView tvProfileGuide, tvLastCondition, tvSpeed, tvSpeedSource;
    private Button btnStart, btnTest, btnProfileAction;
    private RadioGroup profileGroup;
    private View motionRestSection;

    private SharedPreferences preferences;
    private ToneGenerator reminderTone;

    // 차량 속도는 Pleos/Android Automotive 환경에서만 조회한다.
    private static final String CAR_SPEED_PERMISSION = "android.car.permission.CAR_SPEED";
    private static final int SPEED_PERMISSION_REQUEST = 1201;
    private VehicleSpeedReader vehicleSpeedReader;
    private NaviHelperBridge naviHelperBridge;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // 누적 시간: 쉬었을 때는 초기화하지 않고 계속 이어서 계산한다.
    private long elapsedBeforePauseMs = 0L;
    private long segmentStartMs = 0L;
    private long lastRestElapsedMs = 0L;

    private boolean running = false;
    private boolean testMode = false;
    // 멀미 민감 프로필에만 적용되는 상태 확인 주기. 의료 권고 기준이 아닌 시범 설정이다.
    private long lastReminderElapsedMs = 0L;
    private static final int MOTION_CHECK_MINUTES = 12;
    private boolean motionCheckDue = false;
    private int restCount = 0;
    private String history = "";
    private String lastCondition = "상태 확인 기록 없음";

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long elapsed = getElapsedMs();
            updateTimeLabels(elapsed);

            // 일반/어린이 프로필에는 주기적 알림을 보내지 않는다.
            if (isMotionSensitive()) {
                long targetMs = testMode ? 20_000L : MOTION_CHECK_MINUTES * 60_000L;
                if (elapsed - lastReminderElapsedMs >= targetMs) {
                    // 직전 알림 시점을 갱신해, 휴식을 아직 기록하지 않았더라도
                    // 설정한 시간이 지나면 상태 확인 알림이 다시 나타나도록 한다.
                    lastReminderElapsedMs = elapsed;
                    motionCheckDue = true;
                    tvStatus.setText("멀미 상태를 확인해주세요. 불편하면 다음 안전한 장소에서 휴식을 고려하세요.");
                    playSoftReminder();
                }
            }
            updateRestStatus(elapsed);
            handler.postDelayed(this, 500L);
        }
    };

    private boolean isMotionSensitive() {
        return profileGroup != null
                && profileGroup.getCheckedRadioButtonId() == R.id.radioSensitive;
    }

    // 앱이 화면에서 실행 중일 때만 짧은 알림 소리를 재생한다.
    private void playSoftReminder() {
        if (reminderTone != null) {
            reminderTone.startTone(ToneGenerator.TONE_PROP_BEEP, 140);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        preferences = getSharedPreferences("caretrip_v2", MODE_PRIVATE);
        // 일부 가상환경의 오디오 초기화 지연을 피하기 위해 알림음은 잠시 비활성화.
        // 멀미 상태 확인 안내 문구/타이머는 그대로 동작한다.
        reminderTone = null;

        tvTimer = findViewById(R.id.tvTimer);
        tvSinceRest = findViewById(R.id.tvSinceRest);
        tvRestStatus = findViewById(R.id.tvRestStatus);
        motionRestSection = findViewById(R.id.motionRestSection);
        tvTarget = findViewById(R.id.tvTarget);
        tvStatus = findViewById(R.id.tvStatus);
        tvRestCount = findViewById(R.id.tvRestCount);
        tvGuide = findViewById(R.id.tvGuide);
        tvHistory = findViewById(R.id.tvHistory);
        tvInterval = findViewById(R.id.tvInterval);
        tvProfileGuide = findViewById(R.id.tvProfileGuide);
        tvLastCondition = findViewById(R.id.tvLastCondition);
        tvSpeed = findViewById(R.id.tvSpeed);
        tvSpeedSource = findViewById(R.id.tvSpeedSource);
        btnStart = findViewById(R.id.btnStart);
        btnTest = findViewById(R.id.btnTest);
        btnProfileAction = findViewById(R.id.btnProfileAction);
        profileGroup = findViewById(R.id.profileGroup);

        Button btnReset = findViewById(R.id.btnReset);
        Button btnRest = findViewById(R.id.btnRest);
        Button btnComfort = findViewById(R.id.btnComfort);
        Button btnFindRestStop = findViewById(R.id.btnFindRestStop);

        // 데이터 연결 전에는 가짜 속도를 보여주지 않는다.
        tvSpeed.setText("-- km/h");
        tvSpeedSource.setText("차량 속도 연결 전 · Pleos 연동 예정");

        restCount = preferences.getInt("restCount", 0);
        history = preferences.getString("restHistory", "");
        lastCondition = preferences.getString("lastCondition", "상태 확인 기록 없음");

        int initialProfile = preferences.getInt("profileId", R.id.radioAdult);
        if (initialProfile != R.id.radioAdult && initialProfile != R.id.radioChild
                && initialProfile != R.id.radioSensitive) {
            initialProfile = R.id.radioAdult;
        }
        profileGroup.check(initialProfile);

        // 회전해도 현재 열린 앱의 타이머 상태가 유지되도록 복원한다.
        if (savedInstanceState != null) {
            elapsedBeforePauseMs = savedInstanceState.getLong("elapsed", 0L);
            segmentStartMs = savedInstanceState.getLong("segmentStart", 0L);
            lastRestElapsedMs = savedInstanceState.getLong("lastRest", 0L);
            running = savedInstanceState.getBoolean("running", false);
            testMode = savedInstanceState.getBoolean("test", false);
            lastReminderElapsedMs = savedInstanceState.getLong("lastReminder", 0L);
            motionCheckDue = savedInstanceState.getBoolean("motionCheckDue", false);
        }

        profileGroup.setOnCheckedChangeListener((group, checkedId) -> {
            preferences.edit().putInt("profileId", checkedId).apply();
            // 중간에 멀미 민감 프로필로 변경하더라도 전체 주행시간은 유지하고
            // 멀미 상태 확인 알림 주기만 선택 시점부터 새로 계산한다.
            lastReminderElapsedMs = getElapsedMs();
            motionCheckDue = false;
            if (checkedId != R.id.radioSensitive) {
                testMode = false;
                btnTest.setText("20초 테스트 모드 켜기");
                tvStatus.setText(running ? "주행 관리 중" : "출발 준비 완료");
            } else {
                tvStatus.setText(running ? "주행 관리 중 · 멀미 상태 확인 알림 켜짐" : "멀미 상태 확인 알림 준비됨");
            }
            updateProfileDescription();
            updateTargetLabel();
            updateRestStatus(getElapsedMs());
        });

        btnStart.setOnClickListener(v -> {
            if (running) {
                elapsedBeforePauseMs = getElapsedMs();
                running = false;
                handler.removeCallbacks(ticker);
                btnStart.setText("주행 재개");
                tvStatus.setText("주행 관리 일시정지 중");
            } else {
                segmentStartMs = SystemClock.elapsedRealtime();
                running = true;
                btnStart.setText("일시정지");
                tvStatus.setText("주행 관리 시간 측정 중");
                handler.removeCallbacks(ticker);
                handler.post(ticker);
            }
            updateTimeLabels(getElapsedMs());
            updateRestStatus(getElapsedMs());
        });

        // 초기화 버튼은 현재 세션의 '시간'만 초기화한다. 기록은 보존한다.
        btnReset.setOnClickListener(v -> {
            running = false;
            elapsedBeforePauseMs = 0L;
            segmentStartMs = 0L;
            lastRestElapsedMs = 0L;
            lastReminderElapsedMs = 0L;
            motionCheckDue = false;
            handler.removeCallbacks(ticker);
            btnStart.setText("출발하기");
            updateTimeLabels(0L);
            updateRestStatus(0L);
            tvStatus.setText("출발 준비 완료");
        });

        btnTest.setOnClickListener(v -> {
            if (!isMotionSensitive()) return;
            testMode = !testMode;
            // 전체 주행시간과 휴식 기록은 그대로 두고 알림 주기만 초기화한다.
            lastReminderElapsedMs = getElapsedMs();
            motionCheckDue = false;
            btnTest.setText(testMode ? "테스트 모드 끄기" : "20초 테스트 모드 켜기");
            updateTargetLabel();
            updateTimeLabels(getElapsedMs());
            updateRestStatus(getElapsedMs());
            tvStatus.setText(testMode ? "20초 테스트 모드 활성화" : "일반 휴식 간격으로 전환");
        });

        btnRest.setOnClickListener(v -> {
            if (!isMotionSensitive()) return;
            long elapsed = getElapsedMs();
            if (elapsed <= 0L) {
                Toast.makeText(this, "먼저 출발하기를 눌러주세요.", Toast.LENGTH_SHORT).show();
                return;
            }
            restCount++;
            lastRestElapsedMs = elapsed;
            lastReminderElapsedMs = elapsed;
            motionCheckDue = false;

            String timestamp = new SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
                    .format(new Date());
            String entry = timestamp + " · " + currentProfileName()
                    + " · 누적 " + formatElapsed(elapsed);
            history = entry + (history.isEmpty() ? "" : "\n" + history);
            String[] lines = history.split("\n");
            if (lines.length > 5) {
                StringBuilder recent = new StringBuilder();
                for (int i = 0; i < 5; i++) {
                    if (i > 0) recent.append("\n");
                    recent.append(lines[i]);
                }
                history = recent.toString();
            }
            preferences.edit()
                    .putInt("restCount", restCount)
                    .putString("restHistory", history)
                    .apply();
            updateRestHistory();
            updateTimeLabels(elapsed);
            updateRestStatus(elapsed);
            tvStatus.setText(running
                    ? "휴식 기록 완료! 누적 시간은 계속 증가합니다."
                    : "휴식 기록 완료! 주행 관리 시간은 일시정지 상태입니다.");
        });

        btnProfileAction.setOnClickListener(v -> showProfileAction());

        // NaviHelper 연결은 버튼을 누를 때만 시작하여 앱 시작 시 오류를 방지한다.
        btnFindRestStop.setText("내비게이션 연결 · 목적지 확인");
        btnFindRestStop.setOnClickListener(v -> {
            if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
                tvGuide.setText("Pleos 차량용 에뮬레이터에서 내비게이션 연결을 시험해주세요.");
                return;
            }
            if (naviHelperBridge == null) {
                naviHelperBridge = new NaviHelperBridge(this,
                        message -> tvGuide.setText(message));
            }
            naviHelperBridge.checkDestination();
        });

        // 요구한 기존 버튼 ID와 기능 유지: 실제 창문·공조 제어는 하지 않음.
        btnComfort.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Smart Comfort Mode")
                .setItems(new String[]{
                        "지금 정차하기 어려워요",
                        "공조 설정 도움말",
                        "창문 제어는 향후 확장 기능"
                }, (dialog, which) -> {
                    if (which == 0) {
                        tvGuide.setText("무리하게 정차하지 마세요. 안전하게 정차할 수 있는 장소까지 이동하고, "
                                + "동승자가 상태를 확인하도록 도와주세요. 증상이 심하면 안전한 장소에서 즉시 도움을 요청하세요.");
                    } else if (which == 1) {
                        tvGuide.setText("차량 기본 공조 메뉴에서 실내 온도·풍량·환기 상태를 확인하세요. "
                                + "외부 공기 상태와 날씨를 고려해 조정하고, 운전자는 운전 중 화면 조작을 피해주세요.");
                    } else {
                        tvGuide.setText("창문 열기/닫기와 공조 자동 제어는 향후 확장 목표입니다. "
                                + "차량 지원 여부와 제어 권한을 검증하기 전에는 직접 차량을 제어하지 않습니다.");
                    }
                })
                .setNegativeButton("닫기", null)
                .show());

        updateProfileDescription();
        updateTargetLabel();
        updateRestHistory();
        tvLastCondition.setText(lastCondition);
        btnTest.setText(testMode ? "테스트 모드 끄기" : "20초 테스트 모드 켜기");
        btnStart.setText(running ? "일시정지" : (getElapsedMs() > 0L ? "주행 재개" : "출발하기"));
        updateTimeLabels(getElapsedMs());
        updateRestStatus(getElapsedMs());
        if (running) handler.post(ticker);
    }

    private void updateProfileDescription() {
        int id = profileGroup.getCheckedRadioButtonId();
        if (id == R.id.radioChild) {
            tvProfileGuide.setText("어린이 케어 · 보호자가 아이의 상태와 안전벨트, 하차 준비물을 확인합니다. 주기적 휴식 알림은 꺼져 있습니다.");
            btnProfileAction.setText("어린이 상태 체크리스트");
        } else if (id == R.id.radioSensitive) {
            tvProfileGuide.setText("멀미 배려 · 연구에서 보고된 첫 증상 발생 시간 10~15분을 참고해 12분마다 상태 확인을 안내합니다. 의학적으로 정해진 휴식 주기는 아닙니다.");
            btnProfileAction.setText("멀미 불편감 기록");
        } else {
            tvProfileGuide.setText("기본 케어 · 주행 관리와 휴식 기록을 확인합니다. 주기적 멀미 알림은 꺼져 있습니다.");
            btnProfileAction.setText("동승자 상태 확인");
        }
        boolean motionProfile = isMotionSensitive();
        motionRestSection.setVisibility(motionProfile ? View.VISIBLE : View.GONE);
        tvLastCondition.setVisibility(motionProfile ? View.VISIBLE : View.GONE);
        btnTest.setEnabled(motionProfile);
        updateTargetLabel();
    }

    private void showProfileAction() {
        int id = profileGroup.getCheckedRadioButtonId();
        if (id == R.id.radioChild) {
            String[] items = {
                    "어린이의 상태 확인",
                    "안전벨트 착용 상태 확인",
                    "음료·휴식 필요 여부 확인",
                    "하차 전 어린이와 소지품 확인"
            };
            new AlertDialog.Builder(this)
                    .setTitle("어린이 케어 · 안전하게 정차한 후 확인")
                    .setMultiChoiceItems(items, new boolean[items.length], (d, which, checked) -> { })
                    .setPositiveButton("확인", null)
                    .show();
        } else if (id == R.id.radioSensitive) {
            String[] levels = {"괜찮아요", "조금 불편해요", "많이 불편해요"};
            final int[] selected = {-1};
            new AlertDialog.Builder(this)
                    .setTitle("멀미 상태 기록 · 동승자가 입력")
                    .setSingleChoiceItems(levels, -1, (d, which) -> selected[0] = which)
                    .setPositiveButton("기록", (d, which) -> {
                        if (selected[0] < 0) {
                            Toast.makeText(this, "상태를 선택해주세요.", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        lastCondition = "최근 멀미 상태: " + levels[selected[0]];
                        preferences.edit().putString("lastCondition", lastCondition).apply();
                        tvLastCondition.setText(lastCondition);
                        if (selected[0] == 2) {
                            tvStatus.setText("불편감이 큽니다. 안전한 휴식 장소에 정차해 상태를 확인해주세요.");
                        }
                    })
                    .setNegativeButton("취소", null)
                    .show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("일반 동승자 상태 확인")
                    .setMessage("휴식이 필요하거나 몸이 불편하지 않은지 확인하세요. "
                            + "일반 동승자에게는 주기적 멀미 알림을 보내지 않습니다.")
                    .setPositiveButton("확인", null)
                    .show();
        }
    }

    private String currentProfileName() {
        int id = profileGroup.getCheckedRadioButtonId();
        if (id == R.id.radioChild) return "어린이";
        if (id == R.id.radioSensitive) return "멀미 배려";
        return "일반";
    }

    private long getElapsedMs() {
        if (!running) return elapsedBeforePauseMs;
        return elapsedBeforePauseMs + Math.max(0L, SystemClock.elapsedRealtime() - segmentStartMs);
    }

    private String formatElapsed(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        return String.format(Locale.KOREA, "%02d:%02d", seconds / 60L, seconds % 60L);
    }

    private void updateTimeLabels(long elapsed) {
        tvTimer.setText(formatElapsed(elapsed));
        tvSinceRest.setText("최근 휴식 이후 " + formatElapsed(Math.max(0L, elapsed - lastRestElapsedMs)));
    }

    private void updateTargetLabel() {
        tvInterval.setText("12분 자동 설정");
        tvTarget.setText(testMode
                ? "멀미 상태 확인: 20초마다 (테스트)"
                : "멀미 상태 확인: 12분마다 (연구 참고용)");
    }

    private void updateRestStatus(long elapsed) {
        if (!isMotionSensitive()) return;
        if (motionCheckDue) {
            tvRestStatus.setText("휴식 상태: 상태 확인 필요! 불편하면 안전한 곳에서 휴식을 고려하세요.");
            return;
        }
        if (!running && elapsed == 0L) {
            tvRestStatus.setText("휴식 상태: 출발 전 · 12분 간격으로 상태를 확인합니다.");
            return;
        }
        long intervalMs = testMode ? 20_000L : MOTION_CHECK_MINUTES * 60_000L;
        long remainingMs = Math.max(0L, intervalMs - Math.max(0L, elapsed - lastReminderElapsedMs));
        String prefix = running ? "다음 상태 확인까지 " : "일시정지 · 다음 확인까지 ";
        tvRestStatus.setText("휴식 상태: " + prefix + formatElapsed(remainingMs));
    }

    private void updateRestHistory() {
        tvRestCount.setText("휴식 완료: " + restCount + "회");
        tvHistory.setText(history.isEmpty() ? "휴식 완료 기록이 없습니다." : history);
    }

    // 후속 단계: 실제 Pleos 속도 조회 성공 시에만 호출한다.
    @SuppressWarnings("unused")
    private void showActualVehicleSpeed(float speedKmh) {
        if (Float.isNaN(speedKmh) || Float.isInfinite(speedKmh)) return;
        tvSpeed.setText(String.format(Locale.KOREA, "%.0f km/h", Math.abs(speedKmh)));
        tvSpeedSource.setText("차량 속도 연동 중");
    }

    // 실제 차량 속도는 출발하기 버튼과 관계없이 차량 서비스 연결 후 갱신된다.
    @Override
    protected void onStart() {
        super.onStart();
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            tvSpeed.setText("-- km/h");
            tvSpeedSource.setText("스마트폰 테스트: 차량 속도 연결 안 됨");
            return;
        }
        if (checkSelfPermission(CAR_SPEED_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            startVehicleSpeed();
        } else {
            tvSpeed.setText("-- km/h");
            tvSpeedSource.setText("차량 속도 접근 권한 필요");
            requestPermissions(new String[]{CAR_SPEED_PERMISSION}, SPEED_PERMISSION_REQUEST);
        }
    }

    private void startVehicleSpeed() {
        if (vehicleSpeedReader != null) return;
        tvSpeedSource.setText("차량 서비스 연결 중...");
        vehicleSpeedReader = new VehicleSpeedReader(this, new VehicleSpeedReader.Listener() {
            @Override
            public void onSpeed(float speedKmh) {
                showActualVehicleSpeed(speedKmh);
                tvSpeedSource.setText("Pleos 차량 속도 · 실시간 수신");
            }

            @Override
            public void onStatus(String message) {
                if (tvSpeedSource != null) tvSpeedSource.setText(message);
                if (!"차량 속도 신호 대기 중".equals(message) && tvSpeed != null) {
                    tvSpeed.setText("-- km/h");
                }
            }
        });
        vehicleSpeedReader.start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != SPEED_PERMISSION_REQUEST) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startVehicleSpeed();
        } else {
            tvSpeed.setText("-- km/h");
            tvSpeedSource.setText("차량 속도 권한 미승인");
        }
    }

    @Override
    protected void onStop() {
        if (vehicleSpeedReader != null) {
            vehicleSpeedReader.stop();
            vehicleSpeedReader = null;
        }
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putLong("elapsed", running ? elapsedBeforePauseMs : getElapsedMs());
        outState.putLong("segmentStart", segmentStartMs);
        outState.putLong("lastRest", lastRestElapsedMs);
        outState.putBoolean("running", running);
        outState.putBoolean("test", testMode);
        outState.putLong("lastReminder", lastReminderElapsedMs);
        outState.putBoolean("motionCheckDue", motionCheckDue);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (naviHelperBridge != null) {
            naviHelperBridge.release();
            naviHelperBridge = null;
        }
        handler.removeCallbacks(ticker);
        if (reminderTone != null) reminderTone.release();
        super.onDestroy();
    }
}
