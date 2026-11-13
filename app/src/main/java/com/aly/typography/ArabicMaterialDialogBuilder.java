package com.aly.typography;

public final class ArabicMaterialDialogBuilder extends com.google.android.material.dialog.MaterialAlertDialogBuilder {
    public ArabicMaterialDialogBuilder(android.content.Context context) { super(context); }
    public ArabicMaterialDialogBuilder(android.content.Context context, int theme) { super(context, theme); }
    @Override public androidx.appcompat.app.AlertDialog create() {
        androidx.appcompat.app.AlertDialog dialog = super.create();
        ArabicFonts.watch(dialog.getWindow());
        return dialog;
    }
}
