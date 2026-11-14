package app.taleszin.home;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Taleszin Home: tela inicial leve para o tablet KitKat (API 19).
 * Layout todo em código; troca de orientação faz relayout manual (sem recriar a Activity).
 */
public class HomeActivity extends Activity {

    static final int ACCENT = 0xFFFF7A1A;
    static final int TEXT = 0xFFF5F5F5;
    static final int TEXT_DIM = 0xB3FFFFFF;
    static final int SHADOW = 0xAA000000;
    static final Locale PT = new Locale("pt", "BR");
    static final String NOME = "Tales";

    static final String[] DEFAULT_FAVS = {
            "app.mamba.prancheta",
            "org.mozilla.firefox",
            "free.rm.skytube.oss",
            "org.videolan.vlc",
            "net.sourceforge.opencamera",
            "com.martinmimigames.simplefileexplorer",
            "com.android.settings",
    };

    static final int DOCK_ITEM_DP = 80;

    float density;
    PackageManager pm;
    SharedPreferences prefs;
    final Handler ui = new Handler();
    int iconDpi;
    final LruCache<String, Drawable> icons = new LruCache<>(256);

    FrameLayout root;
    LinearLayout home, clockBlock, dockBlock, dockCard, dockRows, drawer;
    TextView greeting, dateView, statusView, dockEmpty, drawerEmpty;
    TextClock clock;
    ImageView appsButton;
    EditText search;
    GridView grid;
    AppAdapter adapter;

    final List<DockItem> dockItems = new ArrayList<>();
    int loadGen;
    boolean appsLoaded;
    int batteryPct = -1;
    boolean charging;

    // gesto de deslizar (abrir/fechar gaveta)
    float downX, downY;
    boolean gestureFired, gridTopAtDown;

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        density = getResources().getDisplayMetrics().density;
        pm = getPackageManager();
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        iconDpi = am.getLauncherLargeIconDensity();

        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);

        buildUi();
        setContentView(root);
        applyOrientation(getResources().getConfiguration());
        refreshDock();
        loadApps();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(packageReceiver, pf);
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(packageReceiver); } catch (IllegalArgumentException ignored) { }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        Intent sticky = registerReceiver(visibleReceiver, f); // devolve o BATTERY_CHANGED sticky
        if (sticky != null) readBattery(sticky);
        updateDateAndGreeting();
        updateStatus();
    }

    @Override
    protected void onPause() {
        try { unregisterReceiver(visibleReceiver); } catch (IllegalArgumentException ignored) { }
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressionado: só fecha a gaveta se estiver aberta.
        if (isDrawerOpen()) closeDrawer();
    }

    @Override
    public void onBackPressed() {
        if (isDrawerOpen()) closeDrawer();
        // na home, voltar não faz nada
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        applyOrientation(c);
    }

    // ------------------------------------------------------------------ receivers

    final BroadcastReceiver visibleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(i.getAction())) {
                int old = batteryPct;
                boolean oldC = charging;
                readBattery(i);
                if (old == batteryPct && oldC == charging) return;
            } else {
                updateDateAndGreeting();
            }
            updateStatus();
        }
    };

    final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            Uri data = i.getData();
            if (data != null) evictIcons(data.getSchemeSpecificPart());
            loadApps();
            refreshDock();
        }
    };

    void readBattery(Intent i) {
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level >= 0 && scale > 0) batteryPct = Math.round(level * 100f / scale);
        int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
    }

    // ------------------------------------------------------------------ UI

    int dp(float v) { return Math.round(v * density); }

    void shadow(TextView t) { t.setShadowLayer(dp(3), 0, dp(1), SHADOW); }

    TextView text(float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        shadow(t);
        return t;
    }

    static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    Drawable pressedBg(float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, rounded(0x33FFFFFF, dp(radiusDp)));
        s.addState(new int[]{android.R.attr.state_focused}, rounded(0x22FF7A1A, dp(radiusDp)));
        s.addState(new int[0], new ColorDrawable(0));
        return s;
    }

    void buildUi() {
        Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
        Typeface medium = Typeface.create("sans-serif", Typeface.BOLD);

        root = new FrameLayout(this);
        root.setFitsSystemWindows(true); // status/nav translúcidas: conteúdo fica fora delas

        // ---- home
        home = new LinearLayout(this);
        home.setLongClickable(true);
        home.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                try {
                    startActivity(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Papel de parede"));
                } catch (ActivityNotFoundException ignored) { }
                return true;
            }
        });
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));

        clockBlock = new LinearLayout(this);
        clockBlock.setOrientation(LinearLayout.VERTICAL);

        greeting = text(18, ACCENT, light);
        clockBlock.addView(greeting);

        clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("h:mm");
        clock.setTextColor(0xFFFFFFFF);
        clock.setTypeface(light);
        clock.setIncludeFontPadding(false);
        shadow(clock);
        clockBlock.addView(clock);

        View bar = new View(this);
        bar.setBackground(rounded(ACCENT, dp(2)));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(40), dp(3));
        barLp.setMargins(dp(4), dp(6), 0, dp(10));
        clockBlock.addView(bar, barLp);

        dateView = text(20, TEXT, null);
        clockBlock.addView(dateView);

        statusView = text(13, TEXT_DIM, null);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-2, -2);
        stLp.topMargin = dp(6);
        clockBlock.addView(statusView, stLp);

        dockBlock = new LinearLayout(this);
        dockBlock.setOrientation(LinearLayout.VERTICAL);

        appsButton = new ImageView(this);
        StateListDrawable ab = new StateListDrawable();
        GradientDrawable abPressed = new GradientDrawable();
        abPressed.setShape(GradientDrawable.OVAL);
        abPressed.setColor(0xFFFFA45E);
        GradientDrawable abNormal = new GradientDrawable();
        abNormal.setShape(GradientDrawable.OVAL);
        abNormal.setColor(ACCENT);
        abNormal.setStroke(dp(2), 0x40000000);
        ab.addState(new int[]{android.R.attr.state_pressed}, abPressed);
        ab.addState(new int[0], abNormal);
        appsButton.setBackground(ab);
        appsButton.setImageDrawable(new DotsDrawable(0xFF1E1006));
        appsButton.setPadding(dp(17), dp(17), dp(17), dp(17));
        appsButton.setContentDescription("Todos os apps");
        appsButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openDrawer(); }
        });
        LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(dp(56), dp(56));
        abLp.gravity = Gravity.CENTER_HORIZONTAL;
        abLp.bottomMargin = dp(12);
        dockBlock.addView(appsButton, abLp);

        dockCard = new LinearLayout(this);
        dockCard.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable card = rounded(0x8C000000, dp(24));
        card.setStroke(Math.max(1, dp(1)), 0x22FFFFFF);
        dockCard.setBackground(card);
        dockCard.setPadding(dp(12), dp(10), dp(12), dp(12));

        TextView header = text(11, ACCENT, medium);
        header.setText("FAVORITOS");
        header.setPadding(dp(8), 0, 0, dp(6));
        dockCard.addView(header);

        dockRows = new LinearLayout(this);
        dockRows.setOrientation(LinearLayout.VERTICAL);
        dockCard.addView(dockRows);

        dockEmpty = text(13, TEXT_DIM, null);
        dockEmpty.setText("Abra os apps e toque e segure um deles\npara fixar aqui.");
        dockEmpty.setPadding(dp(8), dp(4), dp(8), dp(4));
        dockEmpty.setVisibility(View.GONE);
        dockCard.addView(dockEmpty);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-2, -2);
        cardLp.gravity = Gravity.CENTER_HORIZONTAL;
        dockBlock.addView(dockCard, cardLp);

        home.addView(clockBlock);
        home.addView(dockBlock);

        // ---- gaveta
        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(0xE0101418);
        drawer.setClickable(true);
        drawer.setFocusableInTouchMode(true);
        drawer.setPadding(dp(12), dp(12), dp(12), 0);
        drawer.setVisibility(View.GONE);

        search = new EditText(this);
        search.setBackground(rounded(0x1FFFFFFF, dp(24)));
        search.setPadding(dp(20), dp(10), dp(20), dp(10));
        search.setHint("Buscar apps");
        search.setHintTextColor(0x80FFFFFF);
        search.setTextColor(0xFFFFFFFF);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { adapter.filter(s.toString()); }
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
          public boolean onEditorAction(TextView v, int actionId, KeyEvent ev) {
            boolean enter = actionId == EditorInfo.IME_ACTION_GO
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN);
            if (!enter) return false;
            if (adapter.getCount() > 0) launch(adapter.getItem(0));
            return true;
          }
        });
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-1, -2);
        sLp.setMargins(dp(8), dp(4), dp(8), dp(8));
        drawer.addView(search, sLp);

        adapter = new AppAdapter();
        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(88));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(dp(4));
        grid.setSelector(new ColorDrawable(0));
        grid.setDrawSelectorOnTop(false);
        grid.setFastScrollEnabled(true);
        grid.setClipToPadding(false);
        grid.setPadding(0, 0, 0, dp(12));
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) { launch(adapter.getItem(pos)); }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                drawerMenu(v, adapter.getItem(pos));
                return true;
            }
        });
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView v, int state) {
                if (state == SCROLL_STATE_TOUCH_SCROLL) hideKeyboard();
            }
            public void onScroll(AbsListView v, int a, int b, int c) { }
        });
        drawer.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        drawerEmpty = text(15, TEXT_DIM, null);
        drawerEmpty.setGravity(Gravity.CENTER);
        drawerEmpty.setText("Carregando…");
        drawer.addView(drawerEmpty, new LinearLayout.LayoutParams(-1, 0, 1f));
        grid.setEmptyView(drawerEmpty);

        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
    }

    void applyOrientation(Configuration c) {
        boolean land = c.orientation == Configuration.ORIENTATION_LANDSCAPE;
        home.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        if (land) {
            clockBlock.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1f));
            clockBlock.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            clockBlock.setPadding(dp(40), dp(16), dp(16), dp(16));
            dockBlock.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1f));
            dockBlock.setGravity(Gravity.CENTER);
            dockBlock.setPadding(dp(8), dp(16), dp(24), dp(16));
            clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64);
        } else {
            clockBlock.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
            clockBlock.setGravity(Gravity.TOP | Gravity.LEFT);
            clockBlock.setPadding(dp(32), dp(40), dp(32), dp(16));
            dockBlock.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            dockBlock.setGravity(Gravity.CENTER_HORIZONTAL);
            dockBlock.setPadding(dp(16), 0, dp(16), dp(24));
            clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 72);
        }
        buildDockRows(c);
    }

    void updateDateAndGreeting() {
        Date now = new Date();
        dateView.setText(new SimpleDateFormat("EEEE, d 'de' MMMM", PT).format(now));
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g = h >= 5 && h < 12 ? "Bom dia" : h >= 12 && h < 18 ? "Boa tarde" : "Boa noite";
        greeting.setText(g + ", " + NOME);
    }

    void updateStatus() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        StringBuilder sb = new StringBuilder();
        if (batteryPct >= 0) {
            sb.append("Bateria ").append(batteryPct).append('%');
            if (charging) sb.append(" (carregando)");
            sb.append("   ·   ");
        }
        sb.append("RAM livre ").append(mi.availMem / (1024 * 1024)).append(" MB");
        statusView.setText(sb);
    }

    // ------------------------------------------------------------------ dock

    static class DockItem {
        String raw;   // como está salvo (pacote ou componente)
        AppEntry app;
    }

    List<String> readFavs() {
        String s = prefs.getString("favs", null);
        if (s == null) return new ArrayList<>(Arrays.asList(DEFAULT_FAVS));
        List<String> out = new ArrayList<>();
        for (String p : s.split("\n")) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    void saveFavs(List<String> favs) {
        prefs.edit().putString("favs", TextUtils.join("\n", favs)).apply();
    }

    AppEntry resolve(String raw) {
        ComponentName cn;
        if (raw.indexOf('/') >= 0) {
            cn = ComponentName.unflattenFromString(raw);
        } else {
            Intent li = pm.getLaunchIntentForPackage(raw);
            cn = li == null ? null : li.getComponent();
        }
        if (cn == null) return null;
        try {
            ActivityInfo ai = pm.getActivityInfo(cn, 0);
            if (!ai.enabled || !ai.applicationInfo.enabled) return null;
            return new AppEntry(cn, ai, ai.loadLabel(pm).toString().trim());
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    void refreshDock() {
        dockItems.clear();
        for (String raw : readFavs()) {
            AppEntry e = resolve(raw);
            if (e == null) continue; // ausente: pula
            boolean dup = false;
            for (DockItem d : dockItems) if (d.app.cn.equals(e.cn)) dup = true;
            if (dup) continue;
            DockItem d = new DockItem();
            d.raw = raw;
            d.app = e;
            dockItems.add(d);
        }
        buildDockRows(getResources().getConfiguration());
    }

    int maxDockCols(Configuration c) {
        boolean land = c.orientation == Configuration.ORIENTATION_LANDSCAPE;
        int w = c.screenWidthDp;
        int avail = land ? (w / 2 - 8 - 24) : (w - 32);
        avail -= 24 + 2; // padding e borda do cartão
        return Math.max(1, Math.min(avail / DOCK_ITEM_DP, 6));
    }

    void buildDockRows(Configuration c) {
        dockRows.removeAllViews();
        int n = dockItems.size();
        dockEmpty.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        if (n == 0) return;
        int max = maxDockCols(c);
        int rows = (n + max - 1) / max;
        int cols = (n + rows - 1) / rows; // linhas equilibradas (7 → 4+3)
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_HORIZONTAL);
            for (int i = r * cols; i < Math.min(n, (r + 1) * cols); i++) row.addView(dockView(dockItems.get(i)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            if (r > 0) lp.topMargin = dp(4);
            dockRows.addView(row, lp);
        }
    }

    View dockView(final DockItem d) {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setGravity(Gravity.CENTER_HORIZONTAL);
        ll.setPadding(dp(2), dp(6), dp(2), dp(6));
        ll.setBackground(pressedBg(16));
        ll.setFocusable(true);

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Drawable ic = getIcon(d.app);
        Drawable.ConstantState cs = ic.getConstantState();
        iv.setImageDrawable(cs != null ? cs.newDrawable() : ic); // cópia: não dividir bounds com a gaveta
        ll.addView(iv, new LinearLayout.LayoutParams(dp(56), dp(56)));

        TextView tv = text(12, TEXT, null);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        tv.setText(d.app.label);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(4);
        ll.addView(tv, tlp);

        ll.setContentDescription(d.app.label);
        ll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { launch(d.app); }
        });
        ll.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                PopupMenu pop = new PopupMenu(HomeActivity.this, v);
                pop.getMenu().add(0, 1, 0, "Remover dos favoritos");
                pop.getMenu().add(0, 2, 1, "Informações do app");
                pop.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    public boolean onMenuItemClick(MenuItem item) {
                        if (item.getItemId() == 1) removeFav(d.app);
                        else appInfo(d.app.pkg);
                        return true;
                    }
                });
                pop.show();
                return true;
            }
        });
        ll.setLayoutParams(new LinearLayout.LayoutParams(dp(DOCK_ITEM_DP), -2));
        return ll;
    }

    boolean isFav(AppEntry e) {
        for (DockItem d : dockItems) if (d.app.cn.equals(e.cn)) return true;
        return false;
    }

    void addFav(AppEntry e) {
        if (isFav(e)) return;
        List<String> favs = readFavs();
        favs.add(e.key);
        saveFavs(favs);
        refreshDock();
        toast(e.label + " fixado nos favoritos");
    }

    void removeFav(AppEntry e) {
        List<String> favs = readFavs();
        for (DockItem d : dockItems) if (d.app.cn.equals(e.cn)) favs.remove(d.raw);
        saveFavs(favs);
        refreshDock();
    }

    // ------------------------------------------------------------------ gaveta

    boolean isDrawerOpen() { return drawer.getVisibility() == View.VISIBLE; }

    void openDrawer() {
        if (isDrawerOpen()) return;
        if (search.length() > 0) search.setText("");
        else adapter.filter("");
        drawer.setVisibility(View.VISIBLE);
        home.setVisibility(View.INVISIBLE);
        grid.setSelection(0);
        drawer.requestFocus(); // não focar a busca (evita teclado automático)
    }

    void closeDrawer() {
        hideKeyboard();
        drawer.setVisibility(View.GONE);
        home.setVisibility(View.VISIBLE);
        search.setText("");
    }

    void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
        drawer.requestFocus();
    }

    boolean gridAtTop() {
        if (grid.getChildCount() == 0) return true;
        return grid.getFirstVisiblePosition() == 0 && grid.getChildAt(0).getTop() >= grid.getPaddingTop();
    }

    void drawerMenu(View anchor, final AppEntry e) {
        final boolean fav = isFav(e);
        PopupMenu pop = new PopupMenu(this, anchor);
        Menu m = pop.getMenu();
        m.add(0, 1, 0, fav ? "Remover dos favoritos" : "Adicionar aos favoritos");
        m.add(0, 2, 1, "Informações do app");
        if (e.canUninstall) m.add(0, 3, 2, "Desinstalar");
        pop.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
          public boolean onMenuItemClick(MenuItem item) {
            switch (item.getItemId()) {
                case 1: if (fav) removeFav(e); else addFav(e); break;
                case 2: appInfo(e.pkg); break;
                case 3: start(new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", e.pkg, null))); break;
            }
            return true;
          }
        });
        pop.show();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getRawX();
                downY = ev.getRawY();
                gestureFired = false;
                gridTopAtDown = isDrawerOpen() && gridAtTop();
                break;
            case MotionEvent.ACTION_MOVE: {
                if (gestureFired) return true;
                float dy = ev.getRawY() - downY, dx = Math.abs(ev.getRawX() - downX);
                if (!isDrawerOpen()) {
                    if (-dy > dp(72) && dx < -dy) { gestureFired = true; openDrawer(); }
                } else if (gridTopAtDown && dy > dp(96) && dx < dy) {
                    gestureFired = true;
                    closeDrawer();
                }
                if (gestureFired) {
                    MotionEvent cancel = MotionEvent.obtain(ev);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (gestureFired) { gestureFired = false; return true; }
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    // ------------------------------------------------------------------ apps

    static final class AppEntry {
        final ComponentName cn;
        final ActivityInfo ai;
        final String label, key, pkg, norm;
        final boolean canUninstall;

        AppEntry(ComponentName cn, ActivityInfo ai, String label) {
            this.cn = cn;
            this.ai = ai;
            this.label = label;
            this.key = cn.flattenToShortString();
            this.pkg = cn.getPackageName();
            this.norm = norm(label);
            int f = ai.applicationInfo.flags;
            this.canUninstall = (f & ApplicationInfo.FLAG_SYSTEM) == 0
                    || (f & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        }
    }

    static String norm(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(PT);
    }

    void loadApps() {
        final int gen = ++loadGen;
        final String self = getPackageName();
        new Thread(new Runnable() {
          public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(q, 0);
            final ArrayList<AppEntry> list = new ArrayList<>(ris.size());
            for (ResolveInfo ri : ris) {
                ActivityInfo ai = ri.activityInfo;
                if (self.equals(ai.packageName)) continue;
                CharSequence l = ri.loadLabel(pm);
                list.add(new AppEntry(new ComponentName(ai.packageName, ai.name), ai,
                        l == null ? ai.packageName : l.toString().trim()));
            }
            final Collator col = Collator.getInstance(PT);
            col.setStrength(Collator.PRIMARY);
            Collections.sort(list, new Comparator<AppEntry>() {
                public int compare(AppEntry a, AppEntry b) { return col.compare(a.label, b.label); }
            });
            for (AppEntry e : list) {
                if (gen != loadGen) return;
                if (icons.get(e.key) == null) icons.put(e.key, loadIcon(e.ai));
            }
            ui.post(new Runnable() {
                public void run() {
                    if (gen != loadGen) return;
                    appsLoaded = true;
                    drawerEmpty.setText("Nenhum app encontrado");
                    adapter.setAll(list);
                }
            });
          }
        }, "apps-loader").start();
    }

    Drawable loadIcon(ActivityInfo ai) {
        int res = ai.getIconResource();
        if (res != 0) {
            try {
                Resources r = pm.getResourcesForApplication(ai.applicationInfo);
                Drawable d = r.getDrawableForDensity(res, iconDpi);
                if (d != null) return d;
            } catch (Exception ignored) { }
        }
        Drawable d = ai.loadIcon(pm);
        return d != null ? d : pm.getDefaultActivityIcon();
    }

    Drawable getIcon(AppEntry e) {
        Drawable d = icons.get(e.key);
        if (d == null) {
            d = loadIcon(e.ai);
            icons.put(e.key, d);
        }
        return d;
    }

    void evictIcons(String pkg) {
        if (pkg == null) return;
        String prefix = pkg + "/";
        for (String k : icons.snapshot().keySet()) if (k.startsWith(prefix)) icons.remove(k);
    }

    final class AppAdapter extends BaseAdapter {
        List<AppEntry> all = new ArrayList<>();
        List<AppEntry> shown = new ArrayList<>();
        String query = "";

        void setAll(List<AppEntry> list) {
            all = list;
            filter(query);
        }

        void filter(String q) {
            query = q == null ? "" : q;
            String n = norm(query.trim());
            if (n.isEmpty()) {
                shown = all;
            } else {
                List<AppEntry> starts = new ArrayList<>(), contains = new ArrayList<>();
                for (AppEntry e : all) {
                    if (e.norm.startsWith(n)) starts.add(e);
                    else if (e.norm.contains(n)) contains.add(e);
                }
                starts.addAll(contains);
                shown = starts;
            }
            notifyDataSetChanged();
        }

        public int getCount() { return shown.size(); }
        public AppEntry getItem(int i) { return shown.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int pos, View cv, ViewGroup parent) {
            Holder h;
            if (cv == null) {
                LinearLayout ll = new LinearLayout(HomeActivity.this);
                ll.setOrientation(LinearLayout.VERTICAL);
                ll.setGravity(Gravity.CENTER_HORIZONTAL);
                ll.setPadding(dp(4), dp(8), dp(4), dp(6));
                ll.setBackground(pressedBg(16));
                h = new Holder();
                h.icon = new ImageView(HomeActivity.this);
                h.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                ll.addView(h.icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
                h.label = new TextView(HomeActivity.this);
                h.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                h.label.setTextColor(0xEEFFFFFF);
                h.label.setGravity(Gravity.CENTER_HORIZONTAL);
                h.label.setLines(2);
                h.label.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dp(4);
                ll.addView(h.label, lp);
                ll.setTag(h);
                cv = ll;
            } else {
                h = (Holder) cv.getTag();
            }
            AppEntry e = shown.get(pos);
            h.icon.setImageDrawable(getIcon(e));
            h.label.setText(e.label);
            return cv;
        }
    }

    static final class Holder {
        ImageView icon;
        TextView label;
    }

    // ------------------------------------------------------------------ ações

    void launch(AppEntry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(e.cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        start(i);
    }

    void appInfo(String pkg) {
        start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)));
    }

    void start(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException ex) {
            toast("Não foi possível abrir");
        }
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ------------------------------------------------------------------ ícone de grade 3x3

    static final class DotsDrawable extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        DotsDrawable(int color) { p.setColor(color); }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float size = Math.min(b.width(), b.height());
            float step = size / 3f;
            float r = step * 0.22f;
            float x0 = b.exactCenterX() - step, y0 = b.exactCenterY() - step;
            for (int y = 0; y < 3; y++)
                for (int x = 0; x < 3; x++)
                    c.drawCircle(x0 + x * step, y0 + y * step, r, p);
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
