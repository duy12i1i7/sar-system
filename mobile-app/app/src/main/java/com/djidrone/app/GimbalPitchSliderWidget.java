package com.djidrone.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import dji.v5.ux.core.widget.hsi.GimbalPitchBarWidget;

/**
 * Thanh trượt góc nghiêng gimbal (gimbal pitch) kiểu DJI Fly.
 * <p>
 * Kế thừa {@link GimbalPitchBarWidget} của UX SDK (đã đọc sẵn góc pitch realtime),
 * bổ sung hành vi: TỰ HIỆN khi gimbal di chuyển và TỰ ẨN sau một khoảng thời gian
 * không thay đổi.
 */
public class GimbalPitchSliderWidget extends GimbalPitchBarWidget {

    /** Thời gian (ms) không đổi góc thì ẩn thanh trượt. */
    private static final long HIDE_DELAY_MS = 2000L;

    private int lastPitch = Integer.MIN_VALUE;
    private final Handler hideHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideRunnable = this::hideSlider;

    public GimbalPitchSliderWidget(Context context) {
        super(context);
        initHidden();
    }

    public GimbalPitchSliderWidget(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initHidden();
    }

    public GimbalPitchSliderWidget(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initHidden();
    }

    private void initHidden() {
        // Mặc định ẩn (chỉ hiện khi gimbal chuyển động)
        setAlpha(0f);
        setVisibility(View.INVISIBLE);
    }

    @Override
    protected void onValueChanged(int value) {
        super.onValueChanged(value);
        // Chỉ coi là "đang chỉnh" khi giá trị thực sự thay đổi so với lần trước.
        if (lastPitch != Integer.MIN_VALUE && value != lastPitch) {
            showSlider();
        }
        lastPitch = value;
    }

    private void showSlider() {
        if (hideHandler == null) {
            return;
        }
        hideHandler.removeCallbacks(hideRunnable);
        setVisibility(View.VISIBLE);
        animate().cancel();
        animate().alpha(1f).setDuration(120L).start();
        hideHandler.postDelayed(hideRunnable, HIDE_DELAY_MS);
    }

    private void hideSlider() {
        animate().cancel();
        animate().alpha(0f).setDuration(300L)
                .withEndAction(() -> setVisibility(View.INVISIBLE))
                .start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (hideHandler != null) {
            hideHandler.removeCallbacks(hideRunnable);
        }
        super.onDetachedFromWindow();
    }
}
