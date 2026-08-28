/*
 * im-fb.c — FULL-BROWSER の GTK3 IM モジュール (IME ブリッジ)。
 *
 * X11 アプリは Android の IME を直接呼べないため、GTK の入力メソッド機構を
 * 橋渡しに使う。テキスト入力欄のフォーカス in/out を受け取り、状態を
 * /tmp/.fb-ime に書く (1=入力欄にフォーカス, 0=解除)。
 * /tmp はホスト側の <rootfs>/tmp と同一なので、Android 側が inotify で監視して
 * ソフトキーボードを表示/非表示する。
 *
 * 文字の合成 (日本語変換等) は Android 側 (Gboard など) が行い、確定文字列は
 * Termux:X11 (lorie) が X キーイベントとして注入する。本モジュールは
 * GtkIMContextSimple を継承するだけで、キー処理には関与しない。
 *
 * ビルド: gcc -shared -fPIC $(pkg-config --cflags gtk+-3.0) im-fb.c -o im-fb.so
 */
#include <gtk/gtk.h>
#include <gtk/gtkimmodule.h>
#include <stdio.h>
#include <unistd.h>

#define FB_IME_STATE_FILE "/tmp/.fb-ime"

typedef struct { GtkIMContextSimple parent; } FbIMContext;
typedef struct { GtkIMContextSimpleClass parent_class; } FbIMContextClass;

static GType fb_im_context_type = 0;

static void fb_write_state(int focused) {
    FILE *f = fopen(FB_IME_STATE_FILE, "w");
    if (!f)
        return;
    fputc(focused ? '1' : '0', f);
    fclose(f);
}

static void fb_focus_in(GtkIMContext *context) {
    GtkIMContextClass *parent = GTK_IM_CONTEXT_CLASS(g_type_class_peek_parent(
        G_TYPE_INSTANCE_GET_CLASS(context, fb_im_context_type, GtkIMContextClass)));
    fb_write_state(1);
    if (parent && parent->focus_in)
        parent->focus_in(context);
}

static void fb_focus_out(GtkIMContext *context) {
    GtkIMContextClass *parent = GTK_IM_CONTEXT_CLASS(g_type_class_peek_parent(
        G_TYPE_INSTANCE_GET_CLASS(context, fb_im_context_type, GtkIMContextClass)));
    fb_write_state(0);
    if (parent && parent->focus_out)
        parent->focus_out(context);
}

static void fb_im_context_class_init(gpointer klass, gpointer data) {
    (void)data;
    GtkIMContextClass *im_class = GTK_IM_CONTEXT_CLASS(klass);
    im_class->focus_in = fb_focus_in;
    im_class->focus_out = fb_focus_out;
}

static void fb_im_context_init(GTypeInstance *instance, gpointer klass) { (void)instance; (void)klass; }

static const GtkIMContextInfo fb_info = {
    "fb",                       /* context id (GTK_IM_MODULE=fb) */
    "FULL-BROWSER IME bridge",  /* human readable name */
    "im-fb",                    /* translation domain */
    "",                         /* dir */
    "*",                        /* 全ロケール */
};
static const GtkIMContextInfo *fb_info_list[] = { &fb_info };

G_MODULE_EXPORT void im_module_init(GTypeModule *module) {
    const GTypeInfo info = {
        sizeof(FbIMContextClass),
        NULL, NULL,
        fb_im_context_class_init,
        NULL, NULL,
        sizeof(FbIMContext),
        0,
        fb_im_context_init,
        NULL,
    };
    fb_im_context_type = g_type_module_register_type(
        module, GTK_TYPE_IM_CONTEXT_SIMPLE, "FbIMContext", &info, 0);
}

G_MODULE_EXPORT void im_module_exit(void) {}

G_MODULE_EXPORT void im_module_list(const GtkIMContextInfo ***contexts, int *n_contexts) {
    *contexts = fb_info_list;
    *n_contexts = G_N_ELEMENTS(fb_info_list);
}

G_MODULE_EXPORT GtkIMContext *im_module_create(const gchar *context_id) {
    if (g_strcmp0(context_id, "fb") == 0)
        return GTK_IM_CONTEXT(g_object_new(fb_im_context_type, NULL));
    return NULL;
}
