package com.tbruyelle.rxpermissions2;

import android.annotation.TargetApi;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

import io.reactivex.subjects.PublishSubject;

/**
 * 权限申请代理Fragment
 *
 * @author wally
 */
public class RxPermissionsFragment extends Fragment {
    /**
     * 权限申请请求码
     */
    private static final int PERMISSIONS_REQUEST_CODE = 42;

    /**
     * 当前请求的权限结果数据源
     */
    private Map<String, PublishSubject<Permission>> mSubjects = new HashMap<>();
    /**
     * 是否打印Log
     */
    private boolean mLogging;

    public RxPermissionsFragment() {
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //保持实例不销毁
        setRetainInstance(true);
    }

    /**
     * 开始申请权限
     *
     * @param permissions 需要申请的权限列表
     */
    @TargetApi(Build.VERSION_CODES.M)
    void requestPermissions(@NonNull String[] permissions) {
        requestPermissions(permissions, PERMISSIONS_REQUEST_CODE);
    }

    /**
     * 权限申请回调
     *
     * @param requestCode  请求码
     * @param permissions  申请权限列表
     * @param grantResults 申请结果
     */
    @Override
    @TargetApi(Build.VERSION_CODES.M)
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        //忽略不是自己请求的权限回调
        if (requestCode != PERMISSIONS_REQUEST_CODE) {
            return;
        }
        //检查是否需要显示原理
        boolean[] shouldShowRequestPermissionRationale = new boolean[permissions.length];
        for (int i = 0; i < permissions.length; i++) {
            shouldShowRequestPermissionRationale[i] = shouldShowRequestPermissionRationale(permissions[i]);
        }
        //开始处理权限请求结果
        onRequestPermissionsResult(permissions, grantResults, shouldShowRequestPermissionRationale);
    }

    /**
     * 权限结果处理
     *
     * @param permissions                          权限列表
     * @param grantResults                         申请结果
     * @param shouldShowRequestPermissionRationale 是否显示权限缘由列表
     */
    void onRequestPermissionsResult(String[] permissions, int[] grantResults, boolean[] shouldShowRequestPermissionRationale) {
        for (int i = 0, size = permissions.length; i < size; i++) {
            log("onRequestPermissionsResult  " + permissions[i]);
            //用回权限映射找回数据源存根
            PublishSubject<Permission> subject = mSubjects.get(permissions[i]);
            if (subject == null) {
                //一般不会找不到，如果找不到则抛异常
                Log.e(RxPermissions.TAG, "RxPermissions.onRequestPermissionsResult invoked but didn't find the corresponding permission request.");
                return;
            }
            //移除存根
            mSubjects.remove(permissions[i]);
            //判断是否被允许了
            boolean granted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
            //将结果发送回订阅者
            subject.onNext(new Permission(permissions[i], granted, shouldShowRequestPermissionRationale[i]));
            subject.onComplete();
        }
    }

    /**
     * 判断权限是否被允许
     *
     * @param permission 权限
     */
    @TargetApi(Build.VERSION_CODES.M)
    boolean isGranted(String permission) {
        final FragmentActivity fragmentActivity = getActivity();
        if (fragmentActivity == null) {
            throw new IllegalStateException("This fragment must be attached to an activity.");
        }
        return fragmentActivity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 权限被撤销
     *
     * @param permission 权限
     */
    @TargetApi(Build.VERSION_CODES.M)
    boolean isRevoked(String permission) {
        final FragmentActivity fragmentActivity = getActivity();
        if (fragmentActivity == null) {
            throw new IllegalStateException("This fragment must be attached to an activity.");
        }
        return fragmentActivity.getPackageManager().isPermissionRevokedByPolicy(permission, getActivity().getPackageName());
    }

    /**
     * 设置Log开关
     *
     * @param logging 是否允许打印Log
     */
    public void setLogging(boolean logging) {
        mLogging = logging;
    }

    public PublishSubject<Permission> getSubjectByPermission(@NonNull String permission) {
        return mSubjects.get(permission);
    }

    /**
     * 判断权限是否正在申请
     *
     * @param permission 目标权限
     */
    public boolean containsByPermission(@NonNull String permission) {
        return mSubjects.containsKey(permission);
    }

    /**
     * 保存权限申请存根
     *
     * @param permission 权限名
     * @param subject    权限申请存根
     */
    public void setSubjectForPermission(@NonNull String permission, @NonNull PublishSubject<Permission> subject) {
        mSubjects.put(permission, subject);
    }

    /**
     * Log打印
     *
     * @param message 需要打印的信息
     */
    void log(String message) {
        if (mLogging) {
            Log.d(RxPermissions.TAG, message);
        }
    }
}