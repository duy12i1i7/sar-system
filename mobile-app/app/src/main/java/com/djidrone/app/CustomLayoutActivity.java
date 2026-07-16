/*
 * Copyright (c) 2018-2020 DJI
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package com.djidrone.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import dji.sdk.keyvalue.key.BatteryKey;
import dji.sdk.keyvalue.key.CameraKey;
import dji.sdk.keyvalue.key.FlightControllerKey;
import dji.sdk.keyvalue.key.KeyTools;
import dji.sdk.keyvalue.value.camera.CameraMode;
import dji.sdk.keyvalue.value.common.CameraLensType;
import dji.sdk.keyvalue.value.common.LocationCoordinate2D;
import dji.sdk.keyvalue.value.common.ComponentIndexType;
import dji.v5.common.callback.CommonCallbacks;
import dji.v5.common.error.IDJIError;
import dji.v5.manager.KeyManager;
import dji.v5.manager.datacenter.MediaDataCenter;
import dji.v5.manager.interfaces.ICameraStreamManager;
import dji.v5.network.DJINetworkManager;
import dji.v5.network.IDJINetworkStatusListener;
import dji.v5.utils.common.JsonUtil;
import dji.v5.utils.common.LogPath;
import dji.v5.utils.common.LogUtils;
import dji.v5.ux.R;
import dji.v5.ux.accessory.RTKStartServiceHelper;
import dji.v5.ux.cameracore.widget.autoexposurelock.AutoExposureLockWidget;
import dji.v5.ux.cameracore.widget.cameracontrols.CameraControlsWidget;
import dji.v5.ux.cameracore.widget.cameracontrols.lenscontrol.LensControlWidget;
import dji.v5.ux.cameracore.widget.focusexposureswitch.FocusExposureSwitchWidget;
import dji.v5.ux.cameracore.widget.focusmode.FocusModeWidget;
import dji.v5.ux.cameracore.widget.fpvinteraction.FPVInteractionWidget;
import dji.v5.ux.core.base.SchedulerProvider;
import dji.v5.ux.core.communication.BroadcastValues;
import dji.v5.ux.core.communication.GlobalPreferenceKeys;
import dji.v5.ux.core.communication.ObservableInMemoryKeyedStore;
import dji.v5.ux.core.communication.UXKeys;
import dji.v5.ux.core.extension.ViewExtensions;
import dji.v5.ux.core.panel.systemstatus.SystemStatusListPanelWidget;
import dji.v5.ux.core.panel.topbar.TopBarPanelWidget;
import dji.v5.ux.core.util.CameraUtil;
import dji.v5.ux.core.util.DataProcessor;
import dji.v5.ux.core.util.ViewUtil;
import dji.v5.ux.core.widget.fpv.FPVWidget;
import dji.v5.ux.core.widget.hsi.HorizontalSituationIndicatorWidget;
import dji.v5.ux.core.widget.hsi.PrimaryFlightDisplayWidget;
import dji.v5.ux.core.widget.setting.SettingWidget;
import dji.v5.ux.core.widget.simulator.SimulatorIndicatorWidget;
import dji.v5.ux.core.widget.systemstatus.SystemStatusWidget;
import dji.v5.ux.gimbal.GimbalFineTuneWidget;
import dji.v5.ux.map.MapWidget;
import dji.v5.ux.mapkit.core.camera.DJICameraUpdateFactory;
import dji.v5.ux.mapkit.core.maps.DJIMap;
import dji.v5.ux.mapkit.core.maps.DJIUiSettings;
import dji.v5.ux.mapkit.core.models.DJIBitmapDescriptorFactory;
import dji.v5.ux.mapkit.core.models.DJICameraPosition;
import dji.v5.ux.mapkit.core.models.DJILatLng;
import dji.v5.ux.mapkit.core.models.DJILatLngBounds;
import dji.v5.ux.mapkit.core.models.annotations.DJIMarker;
import dji.v5.ux.mapkit.core.models.annotations.DJIMarkerOptions;
import dji.v5.ux.training.simulatorcontrol.SimulatorControlWidget;
import dji.v5.ux.visualcamera.CameraNDVIPanelWidget;
import dji.v5.ux.visualcamera.CameraVisiblePanelWidget;
import dji.v5.ux.visualcamera.ev.CameraConfigEVWidget;
import dji.v5.ux.visualcamera.storage.CameraConfigStorageWidget;
import dji.v5.ux.visualcamera.zoom.FocalZoomWidget;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;

/**
 * Displays a sample layout of widgets similar to that of the various DJI apps.
 */
public class CustomLayoutActivity extends AppCompatActivity {

    //region Fields
    private final String TAG = LogUtils.getTag(this);

    protected FPVWidget primaryFpvWidget;
    protected FPVInteractionWidget fpvInteractionWidget;
    protected FPVWidget secondaryFPVWidget;
    protected SystemStatusListPanelWidget systemStatusListPanelWidget;
    protected SimulatorControlWidget simulatorControlWidget;
    protected LensControlWidget lensControlWidget;
    protected AutoExposureLockWidget autoExposureLockWidget;
    protected FocusModeWidget focusModeWidget;
    protected FocusExposureSwitchWidget focusExposureSwitchWidget;
    protected CameraControlsWidget cameraControlsWidget;
    protected HorizontalSituationIndicatorWidget horizontalSituationIndicatorWidget;
    protected PrimaryFlightDisplayWidget pfvFlightDisplayWidget;
    protected CameraNDVIPanelWidget ndviCameraPanel;
    protected CameraVisiblePanelWidget visualCameraPanel;
    protected CameraConfigStorageWidget cameraConfigStorageWidget;
    protected CameraConfigEVWidget cameraConfigEVWidget;
    protected GimbalPitchSliderWidget gimbalPitchSlider;
    protected FocalZoomWidget focalZoomWidget;
    private boolean videoModeApplied = false;

    // DJI Fly style: điểm đo sáng/EV chỉ hiện khi chạm rồi tự ẩn
    private View exposureMeterView;
    private static final long METER_HIDE_DELAY_MS = 4000L;
    private final Handler meterHideHandler = new Handler(Looper.getMainLooper());
    private final Runnable meterHideRunnable = () -> {
        if (exposureMeterView != null) {
            exposureMeterView.animate().cancel();
            exposureMeterView.animate().alpha(0f).setDuration(250)
                    .withEndAction(() -> exposureMeterView.setVisibility(View.GONE)).start();
        }
    };
    // Vị trí người lái (GPS điện thoại) - UX SDK không có sẵn marker này nên tự thêm
    private DJIMap djiMap;
    private DJIMarker pilotMarker;
    private LocationManager locationManager;

    // Auto-fit bản đồ để luôn thấy đủ: người lái + máy bay + Home.
    // SDK có autoFrameMap nhưng chỉ gom aircraft+home (không biết marker người lái),
    // nên tự tính bounds. Đã set uxsdk_mapCenterLock=NONE để SDK không giành camera.
    private DJILatLng pilotPos;
    private DJILatLng aircraftPos;
    private DJILatLng homePos;
    private long lastMapFitMs = 0L;
    private static final long MAP_FIT_INTERVAL_MS = 2000L;
    private static final float DEFAULT_MAP_ZOOM = 17f;
    private final LocationListener pilotLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            updatePilotMarker(location);
        }

        // Bắt buộc override cho API cũ, tránh crash trên một số máy
        @Override
        public void onProviderEnabled(@NonNull String provider) {
            // không cần xử lý
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            // không cần xử lý
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            // không cần xử lý
        }
    };

    protected SettingWidget settingWidget;
    protected MapWidget mapWidget;
    protected TopBarPanelWidget topBarPanel;
    protected ConstraintLayout fpvParentView;
    private DrawerLayout mDrawerLayout;
    private TextView gimbalAdjustDone;
    private GimbalFineTuneWidget gimbalFineTuneWidget;
    private ComponentIndexType lastDevicePosition = ComponentIndexType.UNKNOWN;
    private CameraLensType lastLensType = CameraLensType.UNKNOWN;
    /** Tag tạm để trace việc bind lại widget theo nguồn camera. */
    private static final String REWIRE_TAG = "DJIREWIRE";
    private static final long REWIRE_RETRY_DELAY_MS = 2500L;
    private static final int REWIRE_MAX_RETRY = 6;
    private final Handler rewireHandler = new Handler(Looper.getMainLooper());



    private CompositeDisposable compositeDisposable;
    private final DataProcessor<CameraSource> cameraSourceProcessor = DataProcessor.create(new CameraSource(ComponentIndexType.UNKNOWN,
            CameraLensType.UNKNOWN));
    private final IDJINetworkStatusListener networkStatusListener = isNetworkAvailable -> {
        if (isNetworkAvailable) {
            LogUtils.d(TAG, "isNetworkAvailable=" + true);
            RTKStartServiceHelper.INSTANCE.startRtkService(false);
        }
    };
    private final ICameraStreamManager.AvailableCameraUpdatedListener availableCameraUpdatedListener = new ICameraStreamManager.AvailableCameraUpdatedListener() {
        @Override
        public void onAvailableCameraUpdated(@NonNull List<ComponentIndexType> availableCameraList) {
            runOnUiThread(() -> updateFPVWidgetSource(availableCameraList));
        }

        @Override
        public void onCameraStreamEnableUpdate(@NonNull Map<ComponentIndexType, Boolean> cameraStreamEnableMap) {
            //
        }
    };

    //endregion

    //region Lifecycle
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.uxsdk_activity_default_layout);
        fpvParentView = findViewById(R.id.fpv_holder);
        mDrawerLayout = findViewById(R.id.root_view);
        topBarPanel = findViewById(R.id.panel_top_bar);
        settingWidget = topBarPanel.getSettingWidget();
        primaryFpvWidget = findViewById(R.id.widget_primary_fpv);
        fpvInteractionWidget = findViewById(R.id.widget_fpv_interaction);
        secondaryFPVWidget = findViewById(R.id.widget_secondary_fpv);
        systemStatusListPanelWidget = findViewById(R.id.widget_panel_system_status_list);
        simulatorControlWidget = findViewById(R.id.widget_simulator_control);
        lensControlWidget = findViewById(R.id.widget_lens_control);
        ndviCameraPanel = findViewById(R.id.panel_ndvi_camera);
        visualCameraPanel = findViewById(R.id.panel_visual_camera);
        autoExposureLockWidget = findViewById(R.id.widget_auto_exposure_lock);
        focusModeWidget = findViewById(R.id.widget_focus_mode);
        focusExposureSwitchWidget = findViewById(R.id.widget_focus_exposure_switch);
        pfvFlightDisplayWidget = findViewById(R.id.widget_fpv_flight_display_widget);
        focalZoomWidget = findViewById(R.id.widget_focal_zoom);
        cameraControlsWidget = findViewById(R.id.widget_camera_controls);
        horizontalSituationIndicatorWidget = findViewById(R.id.widget_horizontal_situation_indicator);
        gimbalAdjustDone = findViewById(R.id.fpv_gimbal_ok_btn);
        gimbalFineTuneWidget = findViewById(R.id.setting_menu_gimbal_fine_tune);
        mapWidget = findViewById(R.id.widget_map);
        cameraConfigStorageWidget = findViewById(com.djidrone.app.R.id.widget_config_storage);
        cameraConfigEVWidget = findViewById(com.djidrone.app.R.id.widget_config_ev);
        gimbalPitchSlider = findViewById(com.djidrone.app.R.id.widget_gimbal_pitch_slider);

        // DJI Fly style: ẩn ô "MENU" (camera settings menu) ở đầu cột điều khiển bên phải
        View cameraSettingsMenu = findViewById(R.id.widget_camera_control_camera_settings_menu);
        if (cameraSettingsMenu != null) {
            cameraSettingsMenu.setVisibility(View.GONE);
        }

        // Ẩn luôn nút chỉnh phơi sáng ngay dưới nút quay/chụp (theo yêu cầu)
        View exposureIndicatorBtn = findViewById(R.id.widget_camera_control_camera_exposure_settings);
        if (exposureIndicatorBtn != null) {
            exposureIndicatorBtn.setVisibility(View.GONE);
        }

        // DJI Fly style: ẩn flight mode nằm trong top bar panel (đã có bản riêng ở góc trái)
        if (topBarPanel.getFlightModeWidget() != null) {
            topBarPanel.getFlightModeWidget().setVisibility(View.GONE);
        }

        // DJI Fly style: điểm đo sáng/EV chỉ hiện khi chạm màn hình rồi tự ẩn
        setupExposureMeterAutoHide();

        // Nút X đóng menu cài đặt vốn KHÔNG được SDK gắn sự kiện -> tự wire để nó đóng drawer
        mDrawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(@NonNull View drawerView) {
                wireSettingsCloseButton(drawerView);
            }
        });

        initClickListener();
        MediaDataCenter.getInstance().getCameraStreamManager().addAvailableCameraUpdatedListener(availableCameraUpdatedListener);
        primaryFpvWidget.setOnFPVStreamSourceListener((devicePosition, lensType) -> cameraSourceProcessor.onNext(new CameraSource(devicePosition, lensType)));

        //小surfaceView放置在顶部，避免被大的遮挡
        secondaryFPVWidget.setSurfaceViewZOrderOnTop(true);
        secondaryFPVWidget.setSurfaceViewZOrderMediaOverlay(true);


        mapWidget.initGoogleMap(map -> {
            djiMap = map;
            DJIUiSettings uiSetting = map.getUiSettings();
            if (uiSetting != null) {
                uiSetting.setZoomControlsEnabled(false);//hide zoom widget
            }
            // Map đã sẵn sàng -> bắt đầu hiện vị trí người lái
            startPilotLocationUpdates();
        });
        mapWidget.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));

        //实现RTK监测网络，并自动重连机制
        DJINetworkManager.getInstance().addNetworkStatusListener(networkStatusListener);

        registerKeyListeners();
        registerForceRecoverReceiverIfDebuggable();
    }

    /**
     * Đăng ký các listener key. Tách riêng để gọi lại được sau khi SDK đăng ký xong
     * hoặc sau khi dựng lại SDK.
     *
     * Vị trí máy bay: cả KeyAircraftLocation lẫn KeyAircraftLocation3D đều KHÔNG phát
     * sự kiện qua listen() (đã kiểm chứng bằng log). MapWidget cũng đọc trực tiếp bằng
     * getValue(), nên ta đọc trực tiếp trong fitMapToAllPositions() thay vì lắng nghe.
     */
    private void registerKeyListeners() {
        // Điểm Home. Toạ độ rác (lat==lon, ngoài dải ±90/±180) = drone CHƯA set Home -> RTH chưa dùng được.
        KeyManager.getInstance().listen(
                KeyTools.createKey(FlightControllerKey.KeyHomeLocation), this,
                (oldValue, newValue) -> {
                    android.util.Log.d(REWIRE_TAG, "HomeLocation = " + newValue);
                    if (newValue != null) {
                        homePos = toValidLatLng(newValue.getLatitude(), newValue.getLongitude());
                        runOnUiThread(this::fitMapToAllPositions);
                    }
                });

        // Hễ máy bay kết nối (lại) là ép wire lại toàn bộ widget phụ thuộc camera,
        // đảm bảo luôn có dữ liệu khi có kết nối máy bay <-> RC.
        KeyManager.getInstance().listen(
                KeyTools.createKey(FlightControllerKey.KeyConnection), this,
                (oldValue, newValue) -> {
                    android.util.Log.d(REWIRE_TAG, "KeyConnection changed: " + oldValue + " -> " + newValue);
                    boolean up = Boolean.TRUE.equals(newValue);
                    if (up) {
                        runOnUiThread(() -> {
                            android.util.Log.d(REWIRE_TAG, "Aircraft CONNECTED -> force re-wire camera widgets");
                            forceRewireCameraWidgets();
                        });
                    }
                });

        // Pin: key nay bien doi lien tuc khi co ket noi -> dung lam bang chung duong
        // ĐẨY (listen) con song hay khong. getValue() van chay khi listen da chet,
        // nen KHONG the dung getValue de do suc khoe duong day.
        KeyManager.getInstance().listen(
                KeyTools.createKey(BatteryKey.KeyChargeRemainingInPercent), this,
                (oldValue, newValue) -> android.util.Log.d(REWIRE_TAG, "PUSH battery = " + newValue));
    }

    /**
     * Ép cập nhật lại nguồn camera cho tất cả widget phụ thuộc camera.
     * Dùng khi resume hoặc khi máy bay kết nối lại, vì onCameraSourceUpdated() sẽ
     * early-return nếu nguồn không đổi -> widget không được bind lại -> mất dữ liệu.
     */
    private void forceRewireCameraWidgets() {
        CameraSource current = cameraSourceProcessor.getValue();
        // Reset để lần phát nguồn camera kế tiếp chắc chắn được APPLY (không bị early-return)
        lastDevicePosition = ComponentIndexType.UNKNOWN;
        lastLensType = CameraLensType.UNKNOWN;
        android.util.Log.d(REWIRE_TAG, "forceRewire: current source = "
                + (current == null ? "null" : current.devicePosition + "/" + current.lensType));
        if (current != null && current.devicePosition != ComponentIndexType.UNKNOWN) {
            onCameraSourceUpdated(current.devicePosition, current.lensType);
            return;
        }
        // Lúc máy bay vừa kết nối, luồng video thường CHƯA lên nên nguồn camera còn UNKNOWN.
        // Bình thường FPV sẽ phát nguồn sau ~2s và APPLY. Nhưng phòng khi nó không phát lại,
        // thử lại vài lần để đảm bảo widget luôn được bind khi đã có kết nối.
        scheduleRewireRetry(1);
    }

    /** Thử bind lại nguồn camera sau vài giây, tối đa {@link #REWIRE_MAX_RETRY} lần. */
    private void scheduleRewireRetry(int attempt) {
        if (attempt > REWIRE_MAX_RETRY) {
            return;
        }
        rewireHandler.postDelayed(() -> {
            CameraSource current = cameraSourceProcessor.getValue();
            boolean stillUnbound = lastDevicePosition == ComponentIndexType.UNKNOWN;
            if (current != null && current.devicePosition != ComponentIndexType.UNKNOWN && stillUnbound) {
                android.util.Log.d(REWIRE_TAG, "rewire retry #" + attempt + " APPLY " + current.devicePosition);
                onCameraSourceUpdated(current.devicePosition, current.lensType);
            } else if (stillUnbound) {
                scheduleRewireRetry(attempt + 1);
            }
        }, REWIRE_RETRY_DELAY_MS);
    }

    //region Watchdog tầng key-value

    /**
     * Hook chỉ có ở bản debug (chặn bằng FLAG_DEBUGGABLE), để truy vết bug N/A:
     * listen() chết mà getValue() vẫn chạy -> widget DJI trống trong khi dữ liệu vẫn có.
     */
    /** Đọc state tầng key ra logcat để test tự động khỏi phải nhìn ảnh chụp màn hình. */
    private static final String ACTION_DUMP_STATE = "com.djidrone.app.DUMP_STATE";
    /** Dang ky lai listener - de kiem chung gia thuyet listener bi mo coi. */
    private static final String ACTION_RELISTEN = "com.djidrone.app.RELISTEN";
    private BroadcastReceiver debugReceiver;

    private void registerForceRecoverReceiverIfDebuggable() {
        boolean debuggable =
                (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (!debuggable) {
            return;
        }
        debugReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ACTION_DUMP_STATE.equals(action)) {
                    dumpKeyState();
                } else if (ACTION_RELISTEN.equals(action)) {
                    android.util.Log.w(REWIRE_TAG, "DEBUG: dang ky LAI toan bo listener");
                    KeyManager.getInstance().cancelListen(CustomLayoutActivity.this);
                    registerKeyListeners();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_DUMP_STATE);
        filter.addAction(ACTION_RELISTEN);
        ContextCompat.registerReceiver(this, debugReceiver, filter,
                ContextCompat.RECEIVER_EXPORTED);
    }

    /** In state ra log dạng máy đọc được: DUMP conn=.. batt=.. sat=.. threads=.. */
    private void dumpKeyState() {
        Object conn = readKey(FlightControllerKey.KeyConnection);
        Object batt = readKey(BatteryKey.KeyChargeRemainingInPercent);
        Object sat = readKey(FlightControllerKey.KeyGPSSatelliteCount);
        android.util.Log.w(REWIRE_TAG, "DUMP conn=" + conn + " batt=" + batt + " sat=" + sat
                + " threads=" + Thread.activeCount()
                + " heapKB=" + ((Runtime.getRuntime().totalMemory()
                        - Runtime.getRuntime().freeMemory()) / 1024));
    }

    private Object readKey(dji.sdk.keyvalue.key.DJIKeyInfo<?> info) {
        try {
            return KeyManager.getInstance().getValue(KeyTools.createKey(info));
        } catch (Exception e) {
            return "ERR:" + e;
        }
    }

    //endregion

    private void isGimableAdjustClicked(BroadcastValues broadcastValues) {
        if (mDrawerLayout.isDrawerOpen(GravityCompat.END)) {
            mDrawerLayout.closeDrawers();
        }
        horizontalSituationIndicatorWidget.setVisibility(View.GONE);
        if (gimbalFineTuneWidget != null) {
            gimbalFineTuneWidget.setVisibility(View.VISIBLE);
        }
    }

    private void initClickListener() {
        secondaryFPVWidget.setOnClickListener(v -> swapVideoSource());

        if (settingWidget != null) {
            settingWidget.setOnClickListener(v -> toggleRightDrawer());
        }

        // Setup top bar state callbacks
        SystemStatusWidget systemStatusWidget = topBarPanel.getSystemStatusWidget();
        if (systemStatusWidget != null) {
            systemStatusWidget.setOnClickListener(v -> ViewExtensions.toggleVisibility(systemStatusListPanelWidget));
        }

        SimulatorIndicatorWidget simulatorIndicatorWidget = topBarPanel.getSimulatorIndicatorWidget();
        if (simulatorIndicatorWidget != null) {
            simulatorIndicatorWidget.setOnClickListener(v -> ViewExtensions.toggleVisibility(simulatorControlWidget));
        }
        gimbalAdjustDone.setOnClickListener(view -> {
            horizontalSituationIndicatorWidget.setVisibility(View.VISIBLE);
            if (gimbalFineTuneWidget != null) {
                gimbalFineTuneWidget.setVisibility(View.GONE);
            }

        });
    }

    /**
     * DJI Fly style: điểm đo sáng + thanh EV (cái "vàng vàng") chỉ hiện khi chạm màn hình,
     * và tự ẩn sau {@link #METER_HIDE_DELAY_MS} ms khi không thao tác.
     */
    private void setupExposureMeterAutoHide() {
        exposureMeterView = fpvInteractionWidget.findViewById(R.id.view_exposure_meter);
        if (exposureMeterView == null) {
            return;
        }
        exposureMeterView.setVisibility(View.GONE);

        // Bọc touch listener của FPV: giữ nguyên logic đo sáng gốc, thêm hiện + hẹn giờ ẩn
        fpvInteractionWidget.setOnTouchListener((v, event) -> {
            boolean handled = fpvInteractionWidget.onTouch(v, event);
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
                showExposureMeter();
            }
            return handled;
        });

        // Kéo thanh EV cũng reset lại hẹn giờ để không bị ẩn giữa chừng
        View seekBar = exposureMeterView.findViewById(R.id.expose_level_seekbar);
        if (seekBar != null) {
            seekBar.setOnTouchListener((v, event) -> {
                resetMeterHideTimer();
                return false; // để seekbar tự xử lý
            });
        }

        // An toàn: nếu SDK tự bật điểm đo sáng lúc khởi động, cũng ẩn sau vài giây
        resetMeterHideTimer();
    }

    private void showExposureMeter() {
        if (exposureMeterView == null) {
            return;
        }
        exposureMeterView.animate().cancel();
        exposureMeterView.setAlpha(1f);
        exposureMeterView.setVisibility(View.VISIBLE);
        resetMeterHideTimer();
    }

    private void resetMeterHideTimer() {
        meterHideHandler.removeCallbacks(meterHideRunnable);
        meterHideHandler.postDelayed(meterHideRunnable, METER_HIDE_DELAY_MS);
    }

    /**
     * Gắn sự kiện cho nút X đóng menu cài đặt (SDK để trống) -> bấm X là đóng drawer.
     * Có thử lại vì fragment header có thể inflate trễ.
     */
    private void wireSettingsCloseButton(View drawerView) {
        if (bindCloseButton(drawerView)) {
            return;
        }
        drawerView.postDelayed(() -> bindCloseButton(drawerView), 300);
    }

    private boolean bindCloseButton(View drawerView) {
        View close = drawerView.findViewById(R.id.setting_menu_header_close);
        if (close != null) {
            close.setOnClickListener(v -> mDrawerLayout.closeDrawers());
            return true;
        }
        return false;
    }

    /**
     * Bắt đầu theo dõi GPS của điện thoại để hiện marker vị trí người lái trên bản đồ.
     * UX SDK chỉ có sẵn 3 marker (home / aircraft / gimbal-yaw), không có marker người lái.
     */
    private void startPilotLocationUpdates() {
        if (djiMap == null || !hasLocationPermission()) {
            return;
        }
        if (locationManager == null) {
            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        }
        if (locationManager == null) {
            return;
        }
        try {
            // Dùng ngay vị trí biết trước để marker hiện lên tức thì, không phải chờ GPS fix
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) {
                last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            if (last != null) {
                updatePilotMarker(last);
            }
            // Nghe cả GPS lẫn NETWORK: trong nhà GPS thường không fix được
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 1f, pilotLocationListener);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 1f, pilotLocationListener);
            }
        } catch (SecurityException e) {
            LogUtils.e(TAG, "Thiếu quyền vị trí: " + e);
        }
    }

    private void stopPilotLocationUpdates() {
        if (locationManager == null) {
            return;
        }
        try {
            locationManager.removeUpdates(pilotLocationListener);
        } catch (SecurityException e) {
            LogUtils.e(TAG, "removeUpdates lỗi: " + e);
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Tạo marker vị trí người lái lần đầu, các lần sau chỉ dời vị trí. */
    private void updatePilotMarker(Location location) {
        if (djiMap == null || location == null) {
            return;
        }
        DJILatLng position = new DJILatLng(location.getLatitude(), location.getLongitude());
        pilotPos = position;
        fitMapToAllPositions();
        if (pilotMarker == null) {
            pilotMarker = djiMap.addMarker(new DJIMarkerOptions()
                    .position(position)
                    .anchor(0.5f, 0.5f)
                    .icon(DJIBitmapDescriptorFactory.fromBitmap(
                            ViewUtil.getBitmapFromVectorDrawable(
                                    ContextCompat.getDrawable(this, com.djidrone.app.R.drawable.ic_pilot_marker)))));
        } else {
            pilotMarker.setPosition(position);
        }
    }

    /**
     * Trả về toạ độ nếu hợp lệ, ngược lại null.
     * Drone trả toạ độ RÁC khi chưa có Home/GPS (vd lat=lon=45836623) -> phải lọc,
     * nếu không bounds sẽ bị kéo ra tận giữa Đại Tây Dương.
     */
    private DJILatLng toValidLatLng(double lat, double lng) {
        boolean valid = lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180
                && !(Math.abs(lat) < 1e-6 && Math.abs(lng) < 1e-6); // loại 0,0 "null island"
        return valid ? new DJILatLng(lat, lng) : null;
    }

    /**
     * Chỉnh camera để LUÔN thấy đủ 3 vị trí: người lái + máy bay + Home.
     * Chỉ gom những điểm hợp lệ; nếu chỉ có 1 điểm thì chỉ cần center vào nó.
     */
    private void fitMapToAllPositions() {
        if (djiMap == null) {
            return;
        }
        // Giới hạn tần suất, tránh camera giật liên tục vì key bắn rất dày
        long now = SystemClock.elapsedRealtime();
        if (now - lastMapFitMs < MAP_FIT_INTERVAL_MS) {
            return;
        }

        // Đọc trực tiếp vị trí máy bay (key này không phát sự kiện qua listen)
        try {
            LocationCoordinate2D acLoc = KeyManager.getInstance()
                    .getValue(KeyTools.createKey(FlightControllerKey.KeyAircraftLocation));
            if (acLoc != null) {
                aircraftPos = toValidLatLng(acLoc.getLatitude(), acLoc.getLongitude());
            }
        } catch (Exception e) {
            android.util.Log.d(REWIRE_TAG, "doc KeyAircraftLocation loi: " + e);
        }

        List<DJILatLng> points = new ArrayList<>();
        if (pilotPos != null) {
            points.add(pilotPos);
        }
        if (aircraftPos != null) {
            points.add(aircraftPos);
        }
        if (homePos != null) {
            points.add(homePos);
        }
        if (points.isEmpty()) {
            return;
        }
        lastMapFitMs = now;
        android.util.Log.d(REWIRE_TAG, "fitMap: pilot=" + pilotPos + " aircraft=" + aircraftPos + " home=" + homePos);

        try {
            if (points.size() == 1) {
                // Một điểm thì không tạo bounds được -> center vào nó ở mức zoom cận cảnh.
                // KHÔNG giữ zoom hiện tại: lúc mới mở app map đang ở zoom thế giới,
                // giữ lại sẽ khiến map đứng nguyên mức nhìn cả châu lục.
                djiMap.animateCamera(DJICameraUpdateFactory.newCameraPosition(
                        new DJICameraPosition.Builder().target(points.get(0)).zoom(DEFAULT_MAP_ZOOM).build()));
                return;
            }
            double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
            double minLng = Double.MAX_VALUE, maxLng = -Double.MAX_VALUE;
            for (DJILatLng p : points) {
                minLat = Math.min(minLat, p.getLatitude());
                maxLat = Math.max(maxLat, p.getLatitude());
                minLng = Math.min(minLng, p.getLongitude());
                maxLng = Math.max(maxLng, p.getLongitude());
            }
            // Khi các điểm quá gần nhau (vd drone còn nằm cạnh người lái), bounds sẽ cực nhỏ
            // -> map zoom sát tối đa và chỉ thấy tile trắng. Ép khung tối thiểu ~55m.
            double minSpan = 0.0005;
            if (maxLat - minLat < minSpan) {
                double midLat = (maxLat + minLat) / 2;
                minLat = midLat - minSpan / 2;
                maxLat = midLat + minSpan / 2;
            }
            if (maxLng - minLng < minSpan) {
                double midLng = (maxLng + minLng) / 2;
                minLng = midLng - minSpan / 2;
                maxLng = midLng + minSpan / 2;
            }

            DJILatLngBounds.Builder builder = new DJILatLngBounds.Builder();
            builder.include(new DJILatLng(minLat, minLng));
            builder.include(new DJILatLng(maxLat, maxLng));
            // padding 60px để marker không dính sát mép khung map nhỏ
            djiMap.animateCamera(DJICameraUpdateFactory.newLatLngBounds(builder.build(), 0, 60));
        } catch (Exception e) {
            android.util.Log.d(REWIRE_TAG, "fitMap lỗi: " + e);
        }
    }

    private void toggleRightDrawer() {
        mDrawerLayout.openDrawer(GravityCompat.END);
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        mapWidget.onDestroy();
        MediaDataCenter.getInstance().getCameraStreamManager().removeAvailableCameraUpdatedListener(availableCameraUpdatedListener);
        DJINetworkManager.getInstance().removeNetworkStatusListener(networkStatusListener);
        KeyManager.getInstance().cancelListen(this);
        meterHideHandler.removeCallbacks(meterHideRunnable);
        rewireHandler.removeCallbacksAndMessages(null);
        if (debugReceiver != null) {
            unregisterReceiver(debugReceiver);
            debugReceiver = null;
        }
    }

    // MapWidget cần 2 callback này để giữ/khôi phục trạng thái bản đồ, trước đây bị thiếu
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        mapWidget.onSaveInstanceState(outState);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapWidget.onLowMemory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapWidget.onResume();

        // QUAN TRỌNG: reset nguồn camera đã ghi nhớ trước khi subscribe lại.
        // onCameraSourceUpdated() có early-return khi nguồn không đổi; nếu không reset thì
        // sau khi thoát ra rồi vào lại app, giá trị phát lại sẽ trùng -> early-return ->
        // các widget phụ thuộc camera (Storage/EV/gimbal...) KHÔNG được wire lại -> mất dữ liệu.
        lastDevicePosition = ComponentIndexType.UNKNOWN;
        lastLensType = CameraLensType.UNKNOWN;

        // Bật lại theo dõi vị trí người lái (đã dừng ở onPause để tiết kiệm pin)
        startPilotLocationUpdates();

        compositeDisposable = new CompositeDisposable();
        compositeDisposable.add(systemStatusListPanelWidget.closeButtonPressed()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(pressed -> {
                    if (pressed) {
                        ViewExtensions.hide(systemStatusListPanelWidget);
                    }
                }));
        compositeDisposable.add(simulatorControlWidget.getUIStateUpdates()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(simulatorControlWidgetState -> {
                    if (simulatorControlWidgetState instanceof SimulatorControlWidget.UIState.VisibilityUpdated) {
                        if (((SimulatorControlWidget.UIState.VisibilityUpdated) simulatorControlWidgetState).isVisible()) {
                            hideOtherPanels(simulatorControlWidget);
                        }
                    }
                }));
        compositeDisposable.add(cameraSourceProcessor.toFlowable()
                .observeOn(SchedulerProvider.io())
                .throttleLast(500, TimeUnit.MILLISECONDS)
                .subscribeOn(SchedulerProvider.io())
                .subscribe(result -> runOnUiThread(() -> onCameraSourceUpdated(result.devicePosition, result.lensType)))
        );
        compositeDisposable.add(ObservableInMemoryKeyedStore.getInstance()
                .addObserver(UXKeys.create(GlobalPreferenceKeys.GIMBAL_ADJUST_CLICKED))
                .observeOn(SchedulerProvider.ui())
                .subscribe(this::isGimableAdjustClicked));
        ViewUtil.setKeepScreen(this, true);
    }

    @Override
    protected void onPause() {
        if (compositeDisposable != null) {
            compositeDisposable.dispose();
            compositeDisposable = null;
        }
        stopPilotLocationUpdates();
        mapWidget.onPause();
        super.onPause();
        ViewUtil.setKeepScreen(this, false);
    }
    //endregion

    private void hideOtherPanels(@Nullable View widget) {
        View[] panels = {
                simulatorControlWidget
        };

        for (View panel : panels) {
            if (widget != panel) {
                panel.setVisibility(View.GONE);
            }
        }
    }

    private void updateFPVWidgetSource(List<ComponentIndexType> availableCameraList) {
        LogUtils.i(TAG, JsonUtil.toJson(availableCameraList));
        if (availableCameraList == null) {
            return;
        }

        ArrayList<ComponentIndexType> cameraList = new ArrayList<>(availableCameraList);

        //没有数据
        if (cameraList.isEmpty()) {
            secondaryFPVWidget.setVisibility(View.GONE);
            return;
        }

        //仅一路数据
        if (cameraList.size() == 1) {
            primaryFpvWidget.updateVideoSource(availableCameraList.get(0));
            secondaryFPVWidget.setVisibility(View.GONE);
            return;
        }

        //大于两路数据
        ComponentIndexType primarySource = getSuitableSource(cameraList, ComponentIndexType.LEFT_OR_MAIN);
        primaryFpvWidget.updateVideoSource(primarySource);
        cameraList.remove(primarySource);

        ComponentIndexType secondarySource = getSuitableSource(cameraList, ComponentIndexType.FPV);
        secondaryFPVWidget.updateVideoSource(secondarySource);

        secondaryFPVWidget.setVisibility(View.VISIBLE);
    }

    private ComponentIndexType getSuitableSource(List<ComponentIndexType> cameraList, ComponentIndexType defaultSource) {
        if (cameraList.contains(ComponentIndexType.LEFT_OR_MAIN)) {
            return ComponentIndexType.LEFT_OR_MAIN;
        } else if (cameraList.contains(ComponentIndexType.RIGHT)) {
            return ComponentIndexType.RIGHT;
        } else if (cameraList.contains(ComponentIndexType.UP)) {
            return ComponentIndexType.UP;
        } else if (cameraList.contains(ComponentIndexType.PORT_1)) {
            return ComponentIndexType.PORT_1;
        } else if (cameraList.contains(ComponentIndexType.PORT_2)) {
            return ComponentIndexType.PORT_2;
        } else if (cameraList.contains(ComponentIndexType.PORT_3)) {
            return ComponentIndexType.PORT_3;
        } else if (cameraList.contains(ComponentIndexType.PORT_4)) {
            return ComponentIndexType.PORT_4;
        } else if (cameraList.contains(ComponentIndexType.VISION_ASSIST)) {
            return ComponentIndexType.VISION_ASSIST;
        }
        return defaultSource;
    }

    private void onCameraSourceUpdated(ComponentIndexType devicePosition, CameraLensType lensType) {
        LogUtils.i(LogPath.SAMPLE, "onCameraSourceUpdated", devicePosition, lensType);
        if (devicePosition == lastDevicePosition && lensType == lastLensType) {
            android.util.Log.d(REWIRE_TAG, "onCameraSourceUpdated SKIPPED (unchanged): " + devicePosition + "/" + lensType);
            return;
        }
        android.util.Log.d(REWIRE_TAG, "onCameraSourceUpdated APPLY: " + devicePosition + "/" + lensType
                + " (was " + lastDevicePosition + "/" + lastLensType + ")");
        lastDevicePosition = devicePosition;
        lastLensType = lensType;
        updateViewVisibility(devicePosition, lensType);
        updateInteractionEnabled();
        //如果无需使能或者显示的，也就没有必要切换了。
        if (fpvInteractionWidget.isInteractionEnabled()) {
            fpvInteractionWidget.updateCameraSource(devicePosition, lensType);
        }
        if (lensControlWidget.getVisibility() == View.VISIBLE) {
            lensControlWidget.updateCameraSource(devicePosition, lensType);
        }
        if (ndviCameraPanel.getVisibility() == View.VISIBLE) {
            ndviCameraPanel.updateCameraSource(devicePosition, lensType);
        }
        if (visualCameraPanel.getVisibility() == View.VISIBLE) {
            visualCameraPanel.updateCameraSource(devicePosition, lensType);
        }
        if (autoExposureLockWidget.getVisibility() == View.VISIBLE) {
            autoExposureLockWidget.updateCameraSource(devicePosition, lensType);
        }
        if (focusModeWidget.getVisibility() == View.VISIBLE) {
            focusModeWidget.updateCameraSource(devicePosition, lensType);
        }
        if (focusExposureSwitchWidget.getVisibility() == View.VISIBLE) {
            focusExposureSwitchWidget.updateCameraSource(devicePosition, lensType);
        }
        if (cameraControlsWidget.getVisibility() == View.VISIBLE) {
            cameraControlsWidget.updateCameraSource(devicePosition, lensType);
        }
        if (focalZoomWidget.getVisibility() == View.VISIBLE) {
            focalZoomWidget.updateCameraSource(devicePosition, lensType);
        }
        if (horizontalSituationIndicatorWidget.getVisibility() == View.VISIBLE) {
            horizontalSituationIndicatorWidget.updateCameraSource(devicePosition, lensType);
        }
        // DJI Fly style: cập nhật thông tin camera góc phải-dưới (Storage / EV)
        if (cameraConfigStorageWidget != null) {
            cameraConfigStorageWidget.updateCameraSource(devicePosition, lensType);
        }
        if (cameraConfigEVWidget != null) {
            cameraConfigEVWidget.updateCameraSource(devicePosition, lensType);
        }
        // DJI Fly style: thanh trượt pitch gimbal lắng nghe đúng camera
        if (gimbalPitchSlider != null) {
            gimbalPitchSlider.updateCameraSource(devicePosition, lensType);
        }
        // DJI Fly style: mặc định vào chế độ VIDEO để nút chụp là vòng tròn ĐỎ (chỉ áp dụng 1 lần)
        applyDefaultVideoMode(devicePosition);
    }

    /**
     * Đặt camera về chế độ quay video một lần duy nhất, để nút record hiển thị màu đỏ như DJI Fly.
     */
    private void applyDefaultVideoMode(ComponentIndexType devicePosition) {
        if (videoModeApplied || CameraUtil.isFPVTypeView(devicePosition) || devicePosition == ComponentIndexType.UNKNOWN) {
            return;
        }
        videoModeApplied = true;
        try {
            KeyManager.getInstance().setValue(
                    KeyTools.createKey(CameraKey.KeyCameraMode, devicePosition),
                    CameraMode.VIDEO_NORMAL,
                    new CommonCallbacks.CompletionCallback() {
                        @Override
                        public void onSuccess() {
                            LogUtils.i(TAG, "Default camera mode set to VIDEO_NORMAL");
                        }

                        @Override
                        public void onFailure(@NonNull IDJIError error) {
                            LogUtils.e(TAG, "Set VIDEO_NORMAL failed: " + error);
                        }
                    });
        } catch (Exception e) {
            LogUtils.e(TAG, "applyDefaultVideoMode error: " + e);
        }
    }

    private void updateViewVisibility(ComponentIndexType devicePosition, CameraLensType lensType) {
        //只在fpv下显示
        pfvFlightDisplayWidget.setVisibility(CameraUtil.isFPVTypeView(devicePosition) ? View.VISIBLE : View.INVISIBLE);

        //fpv下不显示
        lensControlWidget.setVisibility(CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        // DJI Fly style: ẩn thanh camera-config rộng ở giữa-trên (ISO/SHUTTER/F/EV/WB/CAPACITY)
        ndviCameraPanel.setVisibility(View.GONE);
        visualCameraPanel.setVisibility(View.GONE);
        // Ẩn AE lock + AF/MF toggle + nút AF (focus mode).
        // Mini 3 dùng ống kính lấy nét cố định nên AF/MF vô dụng -> bỏ luôn.
        autoExposureLockWidget.setVisibility(View.GONE);
        focusExposureSwitchWidget.setVisibility(View.GONE);
        focusModeWidget.setVisibility(View.GONE);
        cameraControlsWidget.setVisibility(CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        focalZoomWidget.setVisibility(CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        horizontalSituationIndicatorWidget.setSimpleModeEnable(CameraUtil.isFPVTypeView(devicePosition));
    }

    /**
     * Swap the video sources of the FPV and secondary FPV widgets.
     */
    private void swapVideoSource() {
        ComponentIndexType primarySource = primaryFpvWidget.getWidgetModel().getCameraIndex();
        ComponentIndexType secondarySource = secondaryFPVWidget.getWidgetModel().getCameraIndex();
        //两个source都存在的情况下才进行切换
        if (primarySource != ComponentIndexType.UNKNOWN && secondarySource != ComponentIndexType.UNKNOWN) {
            primaryFpvWidget.updateVideoSource(secondarySource);
            secondaryFPVWidget.updateVideoSource(primarySource);
        }
    }

    private void updateInteractionEnabled() {
        fpvInteractionWidget.setInteractionEnabled(!CameraUtil.isFPVTypeView(primaryFpvWidget.getWidgetModel().getCameraIndex()));
    }

    private static class CameraSource {
        ComponentIndexType devicePosition;
        CameraLensType lensType;

        public CameraSource(ComponentIndexType devicePosition, CameraLensType lensType) {
            this.devicePosition = devicePosition;
            this.lensType = lensType;
        }
    }

    @Override
    public void onBackPressed() {
        if (mDrawerLayout.isDrawerOpen(GravityCompat.END)) {
            mDrawerLayout.closeDrawers();
        } else {
            super.onBackPressed();
        }
    }
}
