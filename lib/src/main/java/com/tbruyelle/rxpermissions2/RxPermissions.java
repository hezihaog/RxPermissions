/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tbruyelle.rxpermissions2;

import android.annotation.TargetApi;
import android.app.Activity;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.ObservableTransformer;
import io.reactivex.functions.Function;
import io.reactivex.subjects.PublishSubject;

public class RxPermissions {
    static final String TAG = RxPermissions.class.getSimpleName();
    /**
     * 用于发起Rx数据源的空对象
     */
    static final Object TRIGGER = new Object();

    /**
     * 权限申请代理Fragment
     */
    @VisibleForTesting
    Lazy<RxPermissionsFragment> mRxPermissionsFragment;

    /**
     * 以Activity，构造实例
     */
    public RxPermissions(@NonNull final FragmentActivity activity) {
        mRxPermissionsFragment = getLazySingleton(activity.getSupportFragmentManager());
    }

    /**
     * 以Fragment，构造实例
     */
    public RxPermissions(@NonNull final Fragment fragment) {
        mRxPermissionsFragment = getLazySingleton(fragment.getChildFragmentManager());
    }

    /**
     * 获取懒加载实例
     *
     * @param fragmentManager Fragment管理器
     */
    @NonNull
    private Lazy<RxPermissionsFragment> getLazySingleton(@NonNull final FragmentManager fragmentManager) {
        return new Lazy<RxPermissionsFragment>() {
            private RxPermissionsFragment rxPermissionsFragment;

            @Override
            public synchronized RxPermissionsFragment get() {
                //缓存实例，下次使用直接获取
                if (rxPermissionsFragment == null) {
                    rxPermissionsFragment = getRxPermissionsFragment(fragmentManager);
                }
                return rxPermissionsFragment;
            }
        };
    }

    /**
     * 获取代理Fragment
     *
     * @param fragmentManager Fragment管理器
     */
    private RxPermissionsFragment getRxPermissionsFragment(@NonNull final FragmentManager fragmentManager) {
        //查找Fragment实例
        RxPermissionsFragment rxPermissionsFragment = findRxPermissionsFragment(fragmentManager);
        boolean isNewInstance = rxPermissionsFragment == null;
        //没有找到则创建，再添加
        if (isNewInstance) {
            rxPermissionsFragment = new RxPermissionsFragment();
            fragmentManager
                    .beginTransaction()
                    .add(rxPermissionsFragment, TAG)
                    .commitNow();
        }
        return rxPermissionsFragment;
    }

    /**
     * 查找权限代理Fragment
     *
     * @param fragmentManager Fragment管理器
     */
    private RxPermissionsFragment findRxPermissionsFragment(@NonNull final FragmentManager fragmentManager) {
        return (RxPermissionsFragment) fragmentManager.findFragmentByTag(TAG);
    }

    /**
     * 设置Log打印
     *
     * @param logging 是否打印Log
     */
    public void setLogging(boolean logging) {
        mRxPermissionsFragment.get().setLogging(logging);
    }

    /**
     * 批量申请权限Transformer，可以使用compose操作符连接，全部都授权了才返回true，否则为false，只会通知订阅者一次
     *
     * @param permissions 需要申请的权限
     */
    @SuppressWarnings("WeakerAccess")
    public <T> ObservableTransformer<T, Boolean> ensure(final String... permissions) {
        return new ObservableTransformer<T, Boolean>() {
            @Override
            public ObservableSource<Boolean> apply(Observable<T> o) {
                //申请权限
                return request(o, permissions)
                        //一次性申请，buffer指定一次发射的数量为权限列表数量，所以是一次性申请权限
                        .buffer(permissions.length)
                        .flatMap(new Function<List<Permission>, ObservableSource<Boolean>>() {
                            @Override
                            public ObservableSource<Boolean> apply(List<Permission> permissions) {
                                //申请的权限为空，直接通知订阅者的onComplete
                                if (permissions.isEmpty()) {
                                    return Observable.empty();
                                }
                                //所有权限都允许了，才返回true
                                for (Permission p : permissions) {
                                    //是要有一个没有授权，则返回false
                                    if (!p.granted) {
                                        return Observable.just(false);
                                    }
                                }
                                //所有都允许了
                                return Observable.just(true);
                            }
                        });
            }
        };
    }

    /**
     * 申请权限Transformer，可以使用compose操作符连接，每个申请一次，所以会调用订阅者多次
     *
     * @param permissions 申请的权限列表
     */
    @SuppressWarnings("WeakerAccess")
    public <T> ObservableTransformer<T, Permission> ensureEach(final String... permissions) {
        return new ObservableTransformer<T, Permission>() {
            @Override
            public ObservableSource<Permission> apply(Observable<T> o) {
                return request(o, permissions);
            }
        };
    }

    /**
     * 和ensure类似，都是批量申请权限，但是返回结果不是Boolean，而是Permission对象
     *
     * @param permissions 申请的权限列表
     */
    public <T> ObservableTransformer<T, Permission> ensureEachCombined(final String... permissions) {
        return new ObservableTransformer<T, Permission>() {
            @Override
            public ObservableSource<Permission> apply(Observable<T> o) {
                //申请权限
                return request(o, permissions)
                        //buffer，一次性批量申请所有权限
                        .buffer(permissions.length)
                        .flatMap(new Function<List<Permission>, ObservableSource<Permission>>() {
                            @Override
                            public ObservableSource<Permission> apply(List<Permission> permissions) {
                                if (permissions.isEmpty()) {
                                    return Observable.empty();
                                }
                                //将结果直接发送
                                return Observable.just(new Permission(permissions));
                            }
                        });
            }
        };
    }

    /**
     * 直接发起申请权限，批量申请权限
     *
     * @param permissions 申请的权限列表
     */
    @SuppressWarnings({"WeakerAccess", "unused"})
    public Observable<Boolean> request(final String... permissions) {
        return Observable.just(TRIGGER).compose(ensure(permissions));
    }

    /**
     * 直接发起申请权限，直接发起，每个权限都申请一次，会调用多次订阅者
     *
     * @param permissions 申请的权限列表
     */
    @SuppressWarnings({"WeakerAccess", "unused"})
    public Observable<Permission> requestEach(final String... permissions) {
        return Observable.just(TRIGGER).compose(ensureEach(permissions));
    }

    /**
     * 直接发起申请权限，也是批量申请，但是返回结果不是Boolean而是Permission
     *
     * @param permissions 申请的权限列表
     */
    public Observable<Permission> requestEachCombined(final String... permissions) {
        return Observable.just(TRIGGER).compose(ensureEachCombined(permissions));
    }

    /**
     * 这个request中转很奇怪
     *
     * @param trigger     原始数据源
     * @param permissions 申请的权限
     */
    private Observable<Permission> request(final Observable<?> trigger, final String... permissions) {
        if (permissions == null || permissions.length == 0) {
            throw new IllegalArgumentException("RxPermissions.request/requestEach requires at least one input permission");
        }
        return oneOf(trigger, pending(permissions))
                .flatMap(new Function<Object, Observable<Permission>>() {
                    @Override
                    public Observable<Permission> apply(Object o) {
                        //真正申请权限的实现
                        return requestImplementation(permissions);
                    }
                });
    }

    /**
     * 过滤掉权限和结果数据源不匹配的情况
     *
     * @param permissions 申请的权限
     */
    private Observable<?> pending(final String... permissions) {
        for (String p : permissions) {
            if (!mRxPermissionsFragment.get().containsByPermission(p)) {
                return Observable.empty();
            }
        }
        return Observable.just(TRIGGER);
    }

    /**
     * 合并数据源数据
     */
    private Observable<?> oneOf(Observable<?> trigger, Observable<?> pending) {
        if (trigger == null) {
            return Observable.just(TRIGGER);
        }
        return Observable.merge(trigger, pending);
    }

    /**
     * 真正申请权限
     *
     * @param permissions 申请的权限
     */
    @TargetApi(Build.VERSION_CODES.M)
    private Observable<Permission> requestImplementation(final String... permissions) {
        //权限申请前的结果
        List<Observable<Permission>> list = new ArrayList<>(permissions.length);
        //待申请的权限列表
        List<String> unrequestedPermissions = new ArrayList<>();

        //为每个权限创建一个数据源
        for (String permission : permissions) {
            mRxPermissionsFragment.get().log("Requesting permission " + permission);
            //因为需要申请的权限是固定，这里过滤掉已经允许的权限或者不是运行6.0系统
            if (isGranted(permission)) {
                list.add(Observable.just(new Permission(permission, true, false)));
                continue;
            }
            //被拒绝的权限
            if (isRevoked(permission)) {
                list.add(Observable.just(new Permission(permission, false, false)));
                continue;
            }
            //获取权限申请存根，这种是为了避免快速请求多次，存入了多个结果数据源回调
            PublishSubject<Permission> subject = mRxPermissionsFragment.get().getSubjectByPermission(permission);
            //不存在则创建一个，并保存到代理Fragment
            if (subject == null) {
                //需要申请，添加到待申请的权限
                unrequestedPermissions.add(permission);
                subject = PublishSubject.create();
                mRxPermissionsFragment.get().setSubjectForPermission(permission, subject);
            }
            list.add(subject);
        }
        //如果存在需要申请的权限，则申请权限
        if (!unrequestedPermissions.isEmpty()) {
            //集合转为数组
            String[] unrequestedPermissionsArray = unrequestedPermissions.toArray(new String[unrequestedPermissions.size()]);
            //调用代理Fragment的申请方法
            requestPermissionsFromFragment(unrequestedPermissionsArray);
        }
        //发射允许和被拒绝的权限，concat顺序发送权限结果数据源，将他们的结果按顺序铺平发送
        return Observable.concat(Observable.fromIterable(list));
    }

    @SuppressWarnings("WeakerAccess")
    public Observable<Boolean> shouldShowRequestPermissionRationale(final Activity activity, final String... permissions) {
        //如果当前运行的系统不是6.0，则不管，所以兼容不了国产6.0一下的ROM
        if (!isMarshmallow()) {
            return Observable.just(false);
        }
        return Observable.just(shouldShowRequestPermissionRationaleImplementation(activity, permissions));
    }

    /**
     * 判断是否需要显示申请权限的缘由
     */
    @TargetApi(Build.VERSION_CODES.M)
    private boolean shouldShowRequestPermissionRationaleImplementation(final Activity activity, final String... permissions) {
        for (String p : permissions) {
            //没有允许，并且需要显示申请权限缘由，出现一个需要则返回为
            if (!isGranted(p) && !activity.shouldShowRequestPermissionRationale(p)) {
                return false;
            }
        }
        return true;
    }

    @TargetApi(Build.VERSION_CODES.M)
    void requestPermissionsFromFragment(String[] permissions) {
        mRxPermissionsFragment.get().log("requestPermissionsFromFragment " + TextUtils.join(", ", permissions));
        mRxPermissionsFragment.get().requestPermissions(permissions);
    }

    /**
     * 判断权限是否允许
     *
     * @param permission 目标权限
     */
    @SuppressWarnings("WeakerAccess")
    public boolean isGranted(String permission) {
        return !isMarshmallow() || mRxPermissionsFragment.get().isGranted(permission);
    }

    /**
     * 权限是否拒绝
     *
     * @param permission 目标权限
     */
    @SuppressWarnings("WeakerAccess")
    public boolean isRevoked(String permission) {
        return isMarshmallow() && mRxPermissionsFragment.get().isRevoked(permission);
    }

    /**
     * 判断当前运行的系统是否大于6.0
     */
    boolean isMarshmallow() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    /**
     * 代理申请结果回调
     *
     * @param permissions  申请的权限
     * @param grantResults 申请权限的结果
     */
    void onRequestPermissionsResult(String[] permissions, int[] grantResults) {
        mRxPermissionsFragment.get().onRequestPermissionsResult(permissions, grantResults, new boolean[permissions.length]);
    }

    /**
     * 懒加载接口
     */
    @FunctionalInterface
    public interface Lazy<V> {
        /**
         * 获取懒加载的对象
         *
         * @return 缓存的对象
         */
        V get();
    }
}