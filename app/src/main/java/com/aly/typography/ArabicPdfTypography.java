package com.aly.typography;

/** Covers preference/library DialogFragments without replacing the app's Application. */
public final class ArabicPdfTypography {
    private ArabicPdfTypography() {}
    public static void install(android.app.Application app) {
        app.registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(android.app.Activity activity, android.os.Bundle state) {
                if (!(activity instanceof androidx.fragment.app.FragmentActivity)) return;
                ((androidx.fragment.app.FragmentActivity) activity).getSupportFragmentManager()
                    .registerFragmentLifecycleCallbacks(new androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                        @Override public void onFragmentStarted(androidx.fragment.app.FragmentManager manager, androidx.fragment.app.Fragment fragment) {
                            if (fragment instanceof androidx.fragment.app.DialogFragment) {
                                android.app.Dialog dialog = ((androidx.fragment.app.DialogFragment) fragment).getDialog();
                                if (dialog != null) ArabicFonts.watch(dialog.getWindow());
                            }
                        }
                    }, true);
            }
            @Override public void onActivityStarted(android.app.Activity a) {}
            @Override public void onActivityResumed(android.app.Activity a) {}
            @Override public void onActivityPaused(android.app.Activity a) {}
            @Override public void onActivityStopped(android.app.Activity a) {}
            @Override public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {}
            @Override public void onActivityDestroyed(android.app.Activity a) {}
        });
    }
}
