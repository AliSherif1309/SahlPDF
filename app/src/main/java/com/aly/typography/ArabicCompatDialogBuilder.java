package com.aly.typography;

public final class ArabicCompatDialogBuilder extends androidx.appcompat.app.AlertDialog.Builder {
    public ArabicCompatDialogBuilder(android.content.Context context) { super(context); }
    public ArabicCompatDialogBuilder(android.content.Context context, int theme) { super(context, theme); }
    @Override public androidx.appcompat.app.AlertDialog create() {
        androidx.appcompat.app.AlertDialog dialog = super.create();
        ArabicFonts.watch(dialog.getWindow());
        return dialog;
    }
}
