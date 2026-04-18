package cacom.example.smarthome;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final int MSG_MQTT_MESSAGE = 3;
    private static final int MSG_CONNECT_FAILED = 30;
    private static final int MSG_CONNECT_SUCCESS = 31;

    private static final String MQTT_HOST = "tcp://47.109.89.8:1883";
    private static final String MQTT_USER_NAME = "root23";
    private static final String MQTT_PASSWORD = "root34";

    private static final int TURBIDITY_RANGE = 100;
    private static final int LIGHT_RANGE = 2000;
    private static final int WATER_TEMP_RANGE = 40;
    private static final int WATER_LEVEL_RANGE = 300;
    private static final int PH_RANGE = 14;

    private TextView turbidityValueView;
    private TextView lightValueView;
    private TextView waterTempValueView;
    private TextView waterLevelValueView;
    private TextView phValueView;

    private TextView waterTempThresholdView;
    private TextView waterLevelThresholdView;
    private TextView lightThresholdView;
    private TextView phMaxView;
    private TextView phMinView;
    private TextView turbidityThresholdView;

    private TextView automaticTab;
    private TextView scheduleTab;
    private TextView thresholdTab;
    private TextView manualTab;
    private TextView hourView;
    private TextView minuteView;
    private TextView secondView;
    private TextView durationView;
    private TextView connectionStatusView;
    private TextView topicSummaryView;
    private TextView aiHintView;
    private TextView aiResultView;

    private Switch refillSwitch;
    private Switch oxygenSwitch;
    private Switch changeWaterSwitch;
    private Switch feedSwitch;
    private Switch lightSwitch;
    private Switch buzzerSwitch;
    private Switch timeModeSelectionSwitch;
    private Switch timeModeMasterSwitch;

    private Button aiAnalyzeButton;

    private ProgressBar turbidityProgress;
    private ProgressBar lightProgress;
    private ProgressBar waterTempProgress;
    private ProgressBar waterLevelProgress;
    private ProgressBar phProgress;
    private ProgressBar aiLoadingView;

    private ImageView waterTempThresholdDownView;
    private ImageView waterTempThresholdAddView;
    private ImageView waterLevelThresholdDownView;
    private ImageView waterLevelThresholdAddView;
    private ImageView lightThresholdDownView;
    private ImageView lightThresholdAddView;
    private ImageView turbidityThresholdDownView;
    private ImageView turbidityThresholdAddView;
    private ImageView phMaxDownView;
    private ImageView phMinDownView;
    private ImageView phMinAddView;
    private ImageView phMaxAddView;
    private ImageView hourDownView;
    private ImageView hourAddView;
    private ImageView minuteDownView;
    private ImageView minuteAddView;
    private ImageView timeDownView;
    private ImageView timeAddView;

    private LinearLayout manualPanel;
    private LinearLayout thresholdPanel;
    private LinearLayout schedulePanel;

    private Handler handler;
    private ScheduledExecutorService scheduler;
    private MqttClient client;
    private MqttConnectOptions mqttConnectOptions;

    private SharedPreferences sharedPreferences;
    private AlertDialog loginDialog;
    private AlertDialog temperatureAlertDialog;

    private final String mqttClientId = "android-" + ((int) ((Math.random() * 9 + 1) * 10000));
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    private String mqttSubTopic = "";
    private String mqttPubTopic = "";
    private String latestTelemetrySummary = "";
    private volatile String lastMqttError = "";
    private boolean temperatureAlertTriggered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sharedPreferences = getSharedPreferences("UserData", MODE_PRIVATE);

        controlInitialization();
        initHandler();
        selectModePanel(automaticTab, null);
        updateConnectionStatus(false, "等待 MQTT 连接");
        aiResultView.setText("等待硬件数据上报后，可点击“AI 分析”获取水质建议。");
        aiHintView.setText("AI 将基于实时水温、水位、光照、浑浊度和 PH 数据生成分析。");
        updateTopicSummary();
        showLoginDialog(this::initializeAfterLogin);
    }

    private void initHandler() {
        handler = new Handler(Looper.getMainLooper()) {
            @Override
            @SuppressLint("SetTextI18n")
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                switch (msg.what) {
                    case MSG_MQTT_MESSAGE:
                        parseJsonObject(String.valueOf(msg.obj));
                        break;
                    case MSG_CONNECT_FAILED:
                        String errorMessage = TextUtils.isEmpty(lastMqttError) ? "MQTT 连接失败" : "MQTT 连接失败：" + lastMqttError;
                        updateConnectionStatus(false, errorMessage);
                        Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
                        break;
                    case MSG_CONNECT_SUCCESS:
                        updateConnectionStatus(true, "MQTT 已连接");
                        Toast.makeText(MainActivity.this, "MQTT服务器连接成功，等待硬件数据上报", Toast.LENGTH_SHORT).show();
                        subscribeTopicIfNeeded();
                        break;
                    default:
                        break;
                }
            }
        };
    }

    private void showLoginDialog(Runnable onSuccess) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_login, null);
        EditText usernameInput = dialogView.findViewById(R.id.et_username);
        EditText passwordInput = dialogView.findViewById(R.id.et_password);

        String savedUsername = sharedPreferences.getString("username", "");
        String savedPassword = sharedPreferences.getString("password", "");
        usernameInput.setText(savedUsername);
        passwordInput.setText(savedPassword);

        loginDialog = new AlertDialog.Builder(this)
                .setTitle(savedUsername.isEmpty() ? "首次配置 MQTT 主题" : "确认 MQTT 主题")
                .setView(dialogView)
                .setPositiveButton("连接", null)
                .setNegativeButton("取消", (dialog, which) -> finish())
                .setCancelable(false)
                .create();

        loginDialog.setOnShowListener(dialogInterface -> {
            Button positiveButton = loginDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positiveButton.setOnClickListener(v -> {
                String username = usernameInput.getText().toString().trim();
                String password = passwordInput.getText().toString().trim();

                usernameInput.setError(null);
                passwordInput.setError(null);

                boolean hasError = false;
                if (username.isEmpty()) {
                    usernameInput.setError("不能为空");
                    hasError = true;
                }
                if (password.isEmpty()) {
                    passwordInput.setError("不能为空");
                    hasError = true;
                }

                if (!hasError) {
                    saveUserData(username, password);
                    mqttSubTopic = username;
                    mqttPubTopic = password;
                    updateTopicSummary();
                    loginDialog.dismiss();
                    onSuccess.run();
                }
            });
        });

        loginDialog.show();
    }

    private void saveUserData(String username, String password) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("username", username);
        editor.putString("password", password);
        editor.apply();
    }

    private void initializeAfterLogin() {
        mqttInit();
        listenForEvents();
        updateConnectionStatus(false, "正在连接 MQTT");
        mqttConnect();
        startReconnect();
    }

    private void mqttInit() {
        try {
            client = new MqttClient(MQTT_HOST, mqttClientId, new MemoryPersistence());
            mqttConnectOptions = new MqttConnectOptions();
            mqttConnectOptions.setCleanSession(false);
            mqttConnectOptions.setUserName(MQTT_USER_NAME);
            mqttConnectOptions.setPassword(MQTT_PASSWORD.toCharArray());
            mqttConnectOptions.setConnectionTimeout(10);
            mqttConnectOptions.setKeepAliveInterval(20);

            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    runOnUiThread(() -> updateConnectionStatus(false, "连接中断，正在重连"));
                }

                @Override
                public void messageArrived(String topicName, MqttMessage message) {
                    handler.obtainMessage(MSG_MQTT_MESSAGE, message.toString()).sendToTarget();
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });
        } catch (MqttException e) {
            e.printStackTrace();
            updateConnectionStatus(false, "MQTT 初始化失败");
        }
    }

    private void mqttConnect() {
        new Thread(() -> {
            try {
                if (client != null && !client.isConnected()) {
                    client.connect(mqttConnectOptions);
                    lastMqttError = "";
                    handler.sendEmptyMessage(MSG_CONNECT_SUCCESS);
                }
            } catch (Exception e) {
                e.printStackTrace();
                lastMqttError = e.getMessage();
                handler.sendEmptyMessage(MSG_CONNECT_FAILED);
            }
        }).start();
    }

    private void startReconnect() {
        if (scheduler != null && !scheduler.isShutdown()) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            if (client == null || client.isConnected()) {
                return;
            }
            mqttConnect();
        }, 10, 10, TimeUnit.SECONDS);
    }

    private void subscribeTopicIfNeeded() {
        if (client == null || !client.isConnected() || TextUtils.isEmpty(mqttSubTopic)) {
            return;
        }
        try {
            client.subscribe(mqttSubTopic, 0);
        } catch (MqttException e) {
            e.printStackTrace();
            Toast.makeText(this, "订阅主题失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void publishMessage(String topic, String message) {
        if (client == null || !client.isConnected() || TextUtils.isEmpty(topic)) {
            Toast.makeText(this, "MQTT 未连接，命令未发送", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            client.publish(topic, message.getBytes(StandardCharsets.UTF_8), 0, false);
        } catch (MqttException e) {
            e.printStackTrace();
            Toast.makeText(this, "命令发送失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void parseJsonObject(String jsonText) {
        try {
            JSONObject jsonObject = new JSONObject(jsonText);
            if (isExtendedTelemetry(jsonObject)) {
                applyExtendedTelemetry(jsonObject);
            } else {
                applyLegacyTelemetry(jsonObject);
            }
            aiHintView.setText("最近数据更新时间 " + timeFormat.format(new Date()));
        } catch (JSONException e) {
            e.printStackTrace();
            Toast.makeText(this, "硬件数据解析失败", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isExtendedTelemetry(JSONObject jsonObject) {
        return jsonObject.has("sensor20")
                || jsonObject.has("sensor16")
                || jsonObject.has("sensor17")
                || jsonObject.has("sensor18")
                || jsonObject.has("sensor19");
    }

    private void applyExtendedTelemetry(JSONObject jsonObject) {
        String sensor1 = jsonObject.optString("sensor1", "0");
        String sensor2 = jsonObject.optString("sensor2", "0");
        String sensor3 = jsonObject.optString("sensor3", "0");
        String sensor4 = jsonObject.optString("sensor4", "0");
        String sensor5 = jsonObject.optString("sensor5", "0");
        String sensor6 = jsonObject.optString("sensor6", "0");
        String sensor7 = jsonObject.optString("sensor7", "0");
        String sensor8 = jsonObject.optString("sensor8", "0");
        String sensor9 = jsonObject.optString("sensor9", "0");
        String sensor10 = jsonObject.optString("sensor10", "0");
        String sensor11 = jsonObject.optString("sensor11", "0");
        String sensor12 = jsonObject.optString("sensor12", "0");
        String sensor13 = jsonObject.optString("sensor13", "0");
        String sensor14 = jsonObject.optString("sensor14", "0");
        String sensor15 = jsonObject.optString("sensor15", "0");
        String sensor16 = jsonObject.optString("sensor16", "0");
        String sensor17 = jsonObject.optString("sensor17", "0");
        String sensor18 = jsonObject.optString("sensor18", "0");
        String sensor19 = jsonObject.optString("sensor19", "0");
        String sensor20 = jsonObject.optString("sensor20", "0");

        waterTempValueView.setText(sensor1 + "°C");
        waterLevelValueView.setText(sensor2 + " mm");
        lightValueView.setText(sensor3 + " Lux");
        turbidityValueView.setText(sensor4 + " NTU");
        phValueView.setText(sensor5);

        setScaledProgress(waterTempProgress, parseFloatSafely(sensor1), WATER_TEMP_RANGE);
        setScaledProgress(waterLevelProgress, parseFloatSafely(sensor2), WATER_LEVEL_RANGE);
        setScaledProgress(lightProgress, parseFloatSafely(sensor3), LIGHT_RANGE);
        setScaledProgress(turbidityProgress, parseFloatSafely(sensor4), TURBIDITY_RANGE);
        setScaledProgress(phProgress, parseFloatSafely(sensor5), PH_RANGE);

        waterTempThresholdView.setText(sensor6);
        waterLevelThresholdView.setText(sensor7);
        lightThresholdView.setText(sensor8);
        turbidityThresholdView.setText(sensor9);
        phMaxView.setText(sensor10);
        phMinView.setText(sensor11);

        if ("0".equals(sensor20)) {
            hourView.setText(sensor12);
            minuteView.setText(sensor13);
            secondView.setText(sensor14);
            durationView.setText(sensor15);
        } else {
            hourView.setText(sensor16);
            minuteView.setText(sensor17);
            secondView.setText(sensor18);
            durationView.setText(sensor19);
        }

        latestTelemetrySummary = buildExtendedTelemetrySummary(
                sensor1, sensor2, sensor3, sensor4, sensor5,
                sensor6, sensor7, sensor8, sensor9, sensor10, sensor11,
                sensor12, sensor13, sensor14, sensor15,
                sensor16, sensor17, sensor18, sensor19, sensor20
        );
        checkTemperatureWarning(sensor1, sensor6);
    }

    private void applyLegacyTelemetry(JSONObject jsonObject) {
        String sensor1 = jsonObject.optString("sensor1", "0");
        String sensor2 = jsonObject.optString("sensor2", "0");
        String sensor3 = jsonObject.optString("sensor3", "0");
        String sensor4 = jsonObject.optString("sensor4", "0");
        String sensor5 = jsonObject.optString("sensor5", "0");
        String sensor6 = jsonObject.optString("sensor6", "0");
        String sensor7 = jsonObject.optString("sensor7", "0");
        String sensor8 = jsonObject.optString("sensor8", "0");
        String sensor9 = jsonObject.optString("sensor9", "0");
        String sensor10 = jsonObject.optString("sensor10", "0");
        String sensor11 = jsonObject.optString("sensor11", "0");
        String sensor12 = jsonObject.optString("sensor12", "0");
        String sensor13 = jsonObject.optString("sensor13", "0");
        String sensor14 = jsonObject.optString("sensor14", "0");
        String sensor15 = jsonObject.optString("sensor15", "0");

        waterTempValueView.setText(sensor1 + "°C");
        waterLevelValueView.setText(sensor2 + " mm");
        lightValueView.setText(sensor3 + " Lux");
        turbidityValueView.setText("-- NTU");
        phValueView.setText("--");

        setScaledProgress(waterTempProgress, parseFloatSafely(sensor1), WATER_TEMP_RANGE);
        setScaledProgress(waterLevelProgress, parseFloatSafely(sensor2), WATER_LEVEL_RANGE);
        setScaledProgress(lightProgress, parseFloatSafely(sensor3), LIGHT_RANGE);
        setScaledProgress(turbidityProgress, 0f, TURBIDITY_RANGE);
        setScaledProgress(phProgress, 0f, PH_RANGE);

        waterTempThresholdView.setText(sensor4);
        waterLevelThresholdView.setText(sensor5);
        lightThresholdView.setText(sensor6);
        turbidityThresholdView.setText("--");
        phMaxView.setText("--");
        phMinView.setText("--");

        if ("0".equals(sensor15)) {
            hourView.setText(sensor7);
            minuteView.setText(sensor8);
            secondView.setText(sensor9);
            durationView.setText(sensor10);
        } else {
            hourView.setText(sensor11);
            minuteView.setText(sensor12);
            secondView.setText(sensor13);
            durationView.setText(sensor14);
        }

        latestTelemetrySummary = buildLegacyTelemetrySummary(
                sensor1, sensor2, sensor3, sensor4, sensor5, sensor6,
                sensor7, sensor8, sensor9, sensor10,
                sensor11, sensor12, sensor13, sensor14, sensor15
        );
        checkTemperatureWarning(sensor1, sensor4);
    }

    private void checkTemperatureWarning(String temperatureText, String thresholdText) {
        float currentTemperature = parseFloatSafely(temperatureText);
        float thresholdTemperature = parseFloatSafely(thresholdText);

        if (thresholdTemperature <= 0f) {
            temperatureAlertTriggered = false;
            dismissTemperatureAlertIfShowing();
            return;
        }

        if (currentTemperature > thresholdTemperature) {
            if (!temperatureAlertTriggered) {
                temperatureAlertTriggered = true;
                showTemperatureWarningDialog(currentTemperature, thresholdTemperature);
            }
            return;
        }

        temperatureAlertTriggered = false;
        dismissTemperatureAlertIfShowing();
    }

    private void showTemperatureWarningDialog(float currentTemperature, float thresholdTemperature) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        dismissTemperatureAlertIfShowing();
        String message = String.format(Locale.CHINA,
                "当前水温 %.1f°C，已超过设定阈值 %.1f°C，请及时处理。",
                currentTemperature,
                thresholdTemperature);
        temperatureAlertDialog = new AlertDialog.Builder(this)
                .setTitle("水温预警")
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .setCancelable(true)
                .create();
        temperatureAlertDialog.show();
    }

    private void dismissTemperatureAlertIfShowing() {
        if (temperatureAlertDialog != null && temperatureAlertDialog.isShowing()) {
            temperatureAlertDialog.dismiss();
        }
    }

    private String buildExtendedTelemetrySummary(
            String sensor1,
            String sensor2,
            String sensor3,
            String sensor4,
            String sensor5,
            String sensor6,
            String sensor7,
            String sensor8,
            String sensor9,
            String sensor10,
            String sensor11,
            String sensor12,
            String sensor13,
            String sensor14,
            String sensor15,
            String sensor16,
            String sensor17,
            String sensor18,
            String sensor19,
            String sensor20
    ) {
        String timeModeLabel = "0".equals(sensor20) ? "增氧模式" : "投喂模式";
        String timeSummary;
        if ("0".equals(sensor20)) {
            timeSummary = sensor12 + "时" + sensor13 + "分" + sensor14 + "秒，持续" + sensor15 + "秒";
        } else {
            timeSummary = sensor16 + "时" + sensor17 + "分" + sensor18 + "秒，持续" + sensor19 + "秒";
        }

        return "当前鱼缸监测数据：水温 " + sensor1 + "°C，水位 " + sensor2 + " mm，光照 " + sensor3
                + " Lux，浑浊度 " + sensor4 + " NTU，PH " + sensor5
                + "。阈值设置：水温 " + sensor6 + "，水位 " + sensor7 + "，光照 " + sensor8
                + "，浑浊度 " + sensor9 + "，PH 范围 " + sensor11 + " - " + sensor10
                + "。定时策略：" + timeModeLabel + "，执行时间 " + timeSummary + "。";
    }

    private String buildLegacyTelemetrySummary(
            String sensor1,
            String sensor2,
            String sensor3,
            String sensor4,
            String sensor5,
            String sensor6,
            String sensor7,
            String sensor8,
            String sensor9,
            String sensor10,
            String sensor11,
            String sensor12,
            String sensor13,
            String sensor14,
            String sensor15
    ) {
        String timeModeLabel = "0".equals(sensor15) ? "增氧模式" : "投喂模式";
        String timeSummary;
        if ("0".equals(sensor15)) {
            timeSummary = sensor7 + "时" + sensor8 + "分" + sensor9 + "秒，持续" + sensor10 + "秒";
        } else {
            timeSummary = sensor11 + "时" + sensor12 + "分" + sensor13 + "秒，持续" + sensor14 + "秒";
        }

        return "当前鱼缸监测数据：水温 " + sensor1 + "°C，水位 " + sensor2 + " mm，光照 " + sensor3
                + " Lux。阈值设置：水温 " + sensor4 + "，水位 " + sensor5 + "，光照 " + sensor6
                + "。定时策略：" + timeModeLabel + "，执行时间 " + timeSummary
                + "。当前硬件未上报浑浊度与 PH 数据。";
    }

    private void controlInitialization() {
        turbidityValueView = findViewById(R.id.Sensor4);
        lightValueView = findViewById(R.id.Sensor3);
        waterTempValueView = findViewById(R.id.Sensor2);
        waterLevelValueView = findViewById(R.id.Sensor1);
        phValueView = findViewById(R.id.Sensor5);

        turbidityThresholdView = findViewById(R.id.Sensor4_Threshold);
        lightThresholdView = findViewById(R.id.Sensor3_Threshold);
        waterTempThresholdView = findViewById(R.id.Sensor1_Threshold);
        waterLevelThresholdView = findViewById(R.id.Sensor2_Threshold);
        phMaxView = findViewById(R.id.Sensor5_Threshold);
        phMinView = findViewById(R.id.Sensor6_Threshold);

        hourView = findViewById(R.id.Sensor6);
        minuteView = findViewById(R.id.Sensor7);
        secondView = findViewById(R.id.Sensor8);
        durationView = findViewById(R.id.GOTIME);

        turbidityProgress = findViewById(R.id.ss1);
        lightProgress = findViewById(R.id.ss2);
        waterTempProgress = findViewById(R.id.ss3);
        waterLevelProgress = findViewById(R.id.ss4);
        phProgress = findViewById(R.id.ss5);
        aiLoadingView = findViewById(R.id.ai_loading);

        manualPanel = findViewById(R.id.Mode1);
        thresholdPanel = findViewById(R.id.Mode2);
        schedulePanel = findViewById(R.id.Mode3);

        automaticTab = findViewById(R.id.zidong);
        scheduleTab = findViewById(R.id.dingshi);
        thresholdTab = findViewById(R.id.yuzhi);
        manualTab = findViewById(R.id.shoudong);

        connectionStatusView = findViewById(R.id.tv_connection_status);
        topicSummaryView = findViewById(R.id.tv_topic_summary);
        aiHintView = findViewById(R.id.ai_hint);
        aiResultView = findViewById(R.id.ai_result);
        aiAnalyzeButton = findViewById(R.id.btn_ai_analyze);

        timeModeSelectionSwitch = findViewById(R.id.GoTimeFlag);
        timeModeMasterSwitch = findViewById(R.id.TimeFlag);

        refillSwitch = findViewById(R.id.Switch2);
        oxygenSwitch = findViewById(R.id.Switch3);
        changeWaterSwitch = findViewById(R.id.Switch4);
        feedSwitch = findViewById(R.id.Switch5);
        lightSwitch = findViewById(R.id.Switch6);
        buzzerSwitch = findViewById(R.id.Switch7);

        waterTempThresholdDownView = findViewById(R.id.Sensor1down);
        waterTempThresholdAddView = findViewById(R.id.Sensor1add);
        waterLevelThresholdDownView = findViewById(R.id.Sensor2down);
        waterLevelThresholdAddView = findViewById(R.id.Sensor2add);
        turbidityThresholdDownView = findViewById(R.id.Sensor4down);
        turbidityThresholdAddView = findViewById(R.id.Sensor4add);
        lightThresholdAddView = findViewById(R.id.Sensor3add);
        lightThresholdDownView = findViewById(R.id.Sensor3down);
        phMaxAddView = findViewById(R.id.Sensor8add);
        phMaxDownView = findViewById(R.id.Sensor8down);
        phMinAddView = findViewById(R.id.Sensor9add);
        phMinDownView = findViewById(R.id.Sensor9down);
        hourDownView = findViewById(R.id.Sensor5down);
        hourAddView = findViewById(R.id.Sensor5add);
        minuteDownView = findViewById(R.id.Sensor6down);
        minuteAddView = findViewById(R.id.Sensor6add);
        timeDownView = findViewById(R.id.Sensor7down);
        timeAddView = findViewById(R.id.Sensor7add);
    }

    private void listenForEvents() {
        automaticTab.setOnClickListener(view -> {
            selectModePanel(automaticTab, null);
            publishMessage(mqttPubTopic, "Automatic");
        });

        scheduleTab.setOnClickListener(view -> {
            selectModePanel(scheduleTab, schedulePanel);
            publishMessage(mqttPubTopic, "TimeFlag");
        });

        thresholdTab.setOnClickListener(view -> {
            selectModePanel(thresholdTab, thresholdPanel);
            publishMessage(mqttPubTopic, "ThresholdMODE");
        });

        manualTab.setOnClickListener(view -> {
            selectModePanel(manualTab, manualPanel);
            publishMessage(mqttPubTopic, "Manual");
        });

        timeModeSelectionSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                publishMessage(mqttPubTopic, isChecked ? "OxMode" : "FeedMode"));
        timeModeMasterSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                publishMessage(mqttPubTopic, isChecked ? "TimeON" : "TimeOFF"));

        bindCommand(waterTempThresholdDownView, "WtrTempThresholdDown");
        bindCommand(waterTempThresholdAddView, "WtrTempThresholdAdd");
        bindCommand(waterLevelThresholdDownView, "WtrLevelThresholdDown");
        bindCommand(waterLevelThresholdAddView, "WtrLevelThresholdAdd");
        bindCommand(lightThresholdDownView, "LightThresholdDown");
        bindCommand(lightThresholdAddView, "LightThresholdAdd");
        bindCommand(turbidityThresholdDownView, "TurbThersholdDown");
        bindCommand(turbidityThresholdAddView, "TurbThersholdAdd");
        bindCommand(hourDownView, "HourDown");
        bindCommand(hourAddView, "HourAdd");
        bindCommand(minuteDownView, "MinuteDown");
        bindCommand(minuteAddView, "MinuteAdd");
        bindCommand(timeDownView, "TimeDown");
        bindCommand(timeAddView, "TimeAdd");
        bindCommand(phMinDownView, "PHMinDown");
        bindCommand(phMinAddView, "PHMinAdd");
        bindCommand(phMaxDownView, "PHMaxDown");
        bindCommand(phMaxAddView, "PHMaxAdd");

        bindSwitchCommand(refillSwitch, "Switch2ON", "Switch2OFF");
        bindSwitchCommand(oxygenSwitch, "Switch3ON", "Switch3OFF");
        bindSwitchCommand(changeWaterSwitch, "Switch4ON", "Switch4OFF");
        bindSwitchCommand(feedSwitch, "Switch5ON", "Switch5OFF");
        bindSwitchCommand(lightSwitch, "Switch6ON", "Switch6OFF");
        bindSwitchCommand(buzzerSwitch, "Switch7ON", "Switch7OFF");

        aiAnalyzeButton.setOnClickListener(view -> triggerAiAnalysis());
    }

    private void bindCommand(View view, String command) {
        view.setOnClickListener(v -> publishMessage(mqttPubTopic, command));
    }

    private void bindSwitchCommand(Switch controlSwitch, String onCommand, String offCommand) {
        controlSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                publishMessage(mqttPubTopic, isChecked ? onCommand : offCommand));
    }

    private void selectModePanel(TextView selectedTab, LinearLayout visiblePanel) {
        manualPanel.setVisibility(View.GONE);
        thresholdPanel.setVisibility(View.GONE);
        schedulePanel.setVisibility(View.GONE);

        styleTab(automaticTab, automaticTab == selectedTab);
        styleTab(scheduleTab, scheduleTab == selectedTab);
        styleTab(thresholdTab, thresholdTab == selectedTab);
        styleTab(manualTab, manualTab == selectedTab);

        if (visiblePanel != null) {
            visiblePanel.setVisibility(View.VISIBLE);
        }
    }

    private void styleTab(TextView tab, boolean selected) {
        tab.setBackgroundResource(selected ? R.drawable.tab_active_bg : R.drawable.tab_inactive_bg);
        tab.setTextColor(getColor(selected ? R.color.brand_navy : R.color.text_secondary_dark));
    }

    private void triggerAiAnalysis() {
        if (TextUtils.isEmpty(latestTelemetrySummary)) {
            Toast.makeText(this, "请先等待硬件数据上报后再进行 AI 分析", Toast.LENGTH_SHORT).show();
            return;
        }

        setAiLoading(true);
        new Thread(() -> {
            try {
                String aiResult = requestAiDiagnosis();
                runOnUiThread(() -> aiResultView.setText(aiResult));
            } catch (SocketTimeoutException e) {
                e.printStackTrace();
                runOnUiThread(() -> aiResultView.setText("AI 分析超时：当前网络较慢或模型响应较久，请稍后重试。"));
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> aiResultView.setText("AI 分析失败：" + e.getMessage()));
            } finally {
                runOnUiThread(() -> setAiLoading(false));
            }
        }).start();
    }

    private void setAiLoading(boolean loading) {
        aiLoadingView.setVisibility(loading ? View.VISIBLE : View.GONE);
        aiAnalyzeButton.setEnabled(!loading);
        aiAnalyzeButton.setText(loading ? "分析中..." : "AI 分析");
        if (loading) {
            aiHintView.setText("AI 正在综合判断当前水质与阈值设置...");
        } else if (!TextUtils.isEmpty(latestTelemetrySummary)) {
            aiHintView.setText("最近数据更新时间 " + timeFormat.format(new Date()));
        }
    }

    private String requestAiDiagnosis() throws Exception {
        validateAiConfig();

        JSONObject body = new JSONObject();
        body.put("model", BuildConfig.ARK_MODEL);
        body.put("temperature", 0.2);
        body.put("max_tokens", 480);
        body.put("messages", buildAiMessages());

        HttpURLConnection connection = null;
        try {
            URL url = new URL(BuildConfig.ARK_BASE_URL + "/chat/completions");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(90000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + BuildConfig.ARK_API_KEY);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = connection.getResponseCode();
            String responseBody = readStream(responseCode >= 200 && responseCode < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream());

            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException(extractErrorMessage(responseBody));
            }

            return extractAssistantContent(responseBody);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void validateAiConfig() {
        if (TextUtils.isEmpty(BuildConfig.ARK_API_KEY) || BuildConfig.ARK_API_KEY.contains("REPLACE_WITH")) {
            throw new IllegalStateException("请先在 app/build.gradle 中填入真实方舟 API Key");
        }
        if (TextUtils.isEmpty(BuildConfig.ARK_MODEL)) {
            throw new IllegalStateException("未配置豆包模型 ID");
        }
    }

    private JSONArray buildAiMessages() throws JSONException {
        JSONArray messages = new JSONArray();

        JSONObject systemMessage = new JSONObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", "你是“算智渔防”的智能鱼缸与养殖水质决策助手，不只做监测解读，还要完成“数据→建议→行动”的闭环。"
                + "请用通俗、口语化中文回答，让没有专业背景的养殖户也能直接照做。"
                + "如果多个指标同时异常，必须按风险和处理先后给出优先级，不能只平铺罗列。"
                + "你必须基于当前已有监测数据推荐适合养殖的鱼种，不能因为缺少地域、气候、品种偏好等附加信息而拒绝推荐。"
                + "即使信息不完整，也要根据现有水温、PH、水位、光照、浊度等数据，至少推荐 1 到 3 种较适合的鱼种，并说明推荐理由。"
                + "默认优先级规则为：1. 水位异常，先补水，保障鱼类生存空间；"
                + "2. 溶解氧异常，如提供相关数据则优先增氧；"
                + "3. pH异常，其次调整；"
                + "4. 浊度异常，最后处理。"
                + "例如当水位偏低且 pH 偏低时，要先建议补水，待水位恢复后再建议换水或调节 pH。"
                + "如果缺少地域或气候信息，可以说明“这是基于当前监测数据的通用推荐”，但仍然必须给出鱼种推荐，不能回答“无法推荐”。"
                + "输出格式固定为："
                + "【总体判断】一句话结论；"
                + "【推荐鱼种】列出 1 到 3 种推荐鱼种，每种用一句话说明为什么适合当前数据；"
                + "【优先处理】按 1. 2. 3. 列出异常项、风险原因和先后顺序；"
                + "【建议动作】给出明确动作、对应设备、持续时间或复测时机；"
                + "【补充说明】说明缺失数据、人工复核点或注意事项。"
                + "整体保持简洁，但必须能直接执行。");
        messages.put(systemMessage);

        JSONObject userMessage = new JSONObject();
        userMessage.put("role", "user");
        userMessage.put("content", latestTelemetrySummary
                + "\n可用设备：补水、增氧、换水、投喂、灯光、蜂鸣器。"
                + "\n请只根据当前已有监测数据进行判断，即使没有地域、气候、历史养殖记录，也必须推荐适合鱼种。"
                + "\n不允许回答“因为缺少其他数据所以不能推荐鱼种”，必须给出鱼种建议。"
                + "\n如果多个阈值同时异常，请明确写出先做什么、后做什么，以及每一步对应的设备动作。"
                + "\n如果没有溶解氧等传感器数据，请直接说明“当前无该数据”，不要编造数值。");
        messages.put(userMessage);

        return messages;
    }

    private String extractAssistantContent(String responseBody) throws JSONException {
        JSONObject response = new JSONObject(responseBody);
        JSONArray choices = response.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new JSONException("模型未返回有效内容");
        }

        JSONObject firstChoice = choices.optJSONObject(0);
        if (firstChoice == null) {
            throw new JSONException("模型返回结构异常");
        }

        JSONObject message = firstChoice.optJSONObject("message");
        if (message == null) {
            throw new JSONException("模型消息缺失");
        }

        Object content = message.opt("content");
        String extracted = normalizeContent(content).trim();
        if (TextUtils.isEmpty(extracted)) {
            throw new JSONException("模型返回为空");
        }
        return extracted;
    }

    private String normalizeContent(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String) {
            return (String) content;
        }
        if (content instanceof JSONArray) {
            StringBuilder builder = new StringBuilder();
            JSONArray contentArray = (JSONArray) content;
            for (int i = 0; i < contentArray.length(); i++) {
                JSONObject item = contentArray.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String text = item.optString("text");
                if (TextUtils.isEmpty(text)) {
                    JSONObject innerText = item.optJSONObject("text");
                    if (innerText != null) {
                        text = innerText.optString("value");
                    }
                }
                if (!TextUtils.isEmpty(text)) {
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(text.trim());
                }
            }
            return builder.toString();
        }
        return String.valueOf(content);
    }

    private String extractErrorMessage(String responseBody) {
        if (TextUtils.isEmpty(responseBody)) {
            return "服务无返回内容";
        }
        try {
            JSONObject errorRoot = new JSONObject(responseBody);
            JSONObject errorObject = errorRoot.optJSONObject("error");
            if (errorObject != null) {
                String message = errorObject.optString("message");
                if (!TextUtils.isEmpty(message)) {
                    return message;
                }
            }
            String message = errorRoot.optString("message");
            if (!TextUtils.isEmpty(message)) {
                return message;
            }
        } catch (JSONException ignored) {
        }
        return responseBody;
    }

    private String readStream(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private void setScaledProgress(ProgressBar progressBar, float value, float maxRange) {
        int scaled = 0;
        if (maxRange > 0) {
            scaled = Math.round(Math.max(0f, Math.min(value, maxRange)) * 100f / maxRange);
        }
        progressBar.setProgress(scaled);
    }

    private float parseFloatSafely(String text) {
        try {
            return Float.parseFloat(text);
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private void updateConnectionStatus(boolean connected, String message) {
        connectionStatusView.setText(connected ? "在线" : "离线");
        connectionStatusView.setBackgroundResource(connected ? R.drawable.tab_active_bg : R.drawable.tab_inactive_bg);
        connectionStatusView.setTextColor(getColor(connected ? R.color.brand_navy : R.color.text_secondary_dark));
        topicSummaryView.setText(message + "\n" + buildTopicText());
    }

    private void updateTopicSummary() {
        if (topicSummaryView != null) {
            topicSummaryView.setText("等待主题确认\n" + buildTopicText());
        }
    }

    private String buildTopicText() {
        String sub = TextUtils.isEmpty(mqttSubTopic) ? "未设置" : mqttSubTopic;
        String pub = TextUtils.isEmpty(mqttPubTopic) ? "未设置" : mqttPubTopic;
        return "订阅主题：" + sub + "   发布主题：" + pub;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dismissTemperatureAlertIfShowing();
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (client != null) {
            try {
                if (client.isConnected()) {
                    client.disconnect();
                }
                client.close();
            } catch (MqttException e) {
                e.printStackTrace();
            }
        }
    }
}
