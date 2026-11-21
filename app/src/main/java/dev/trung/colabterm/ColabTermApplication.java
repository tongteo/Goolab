package dev.trung.colabterm;

import com.chaquo.python.android.PyApplication;
public final class ColabTermApplication extends PyApplication {
    @Override
    public void onCreate() {
        AppDiagnostics.install(this);
        if (AppDiagnostics.capturePreviousExit(this) || AppDiagnostics.hasPendingCrash(this)) {
            // Keep recovery independent of the initialization which may have crashed.
            return;
        }
        AppDiagnostics.beginStartup(this);
        AppDiagnostics.mark(this, "Application.onCreate entered");
        super.onCreate();
        AppDiagnostics.mark(this, "Chaquopy initialization completed");
        AndroidTokenStore.initialize(this);
        AppDiagnostics.mark(this, "Android token store initialization completed");
    }
}
