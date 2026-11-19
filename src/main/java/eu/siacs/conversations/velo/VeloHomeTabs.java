package eu.siacs.conversations.velo;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.color.MaterialColors;

import java.util.function.LongConsumer;

import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.ConversationsActivity;

/** Velo 0.28: the tabs above the chat list. Same filters as the side menu. */
public final class VeloHomeTabs {

    private static final long[] IDS = {
        ConversationsActivity.DRAWER_ALL_CHATS,
        ConversationsActivity.DRAWER_UNREAD_CHATS,
        ConversationsActivity.DRAWER_DIRECT_MESSAGES,
        ConversationsActivity.DRAWER_CHANNELS
    };
    private static final int[] NAMES = {
        R.string.velo_tab_all, R.string.velo_tab_unread, R.string.velo_tab_direct, R.string.velo_tab_channels
    };

    private VeloHomeTabs() {}

    /**
     * Velo 0.44: {@code requests} chats from strangers wait in their own tab, shown only while
     * there are some (or while it is open). Up to 0.43 they were reachable from the side menu only.
     */
    public static void fill(final LinearLayout row, final long current, final int unread, final int requests, final LongConsumer pick) {
        final Context context = row.getContext();
        final float density = context.getResources().getDisplayMetrics().density;
        final int primary = MaterialColors.getColor(row, androidx.appcompat.R.attr.colorPrimary);
        final int onPrimary = MaterialColors.getColor(row, com.google.android.material.R.attr.colorOnPrimary);
        final int muted = MaterialColors.getColor(row, com.google.android.material.R.attr.colorOnSurfaceVariant);
        row.removeAllViews();
        final boolean withRequests = requests > 0 || current == ConversationsActivity.DRAWER_CHAT_REQUESTS;
        final int tabs = IDS.length + (withRequests ? 1 : 0);
        for (int i = 0; i < tabs; i++) {
            final boolean requestsTab = i == IDS.length;
            final long id = requestsTab ? ConversationsActivity.DRAWER_CHAT_REQUESTS : IDS[i];
            final boolean selected = id == current;
            final int count = requestsTab ? requests : (i == 0 ? unread : 0);
            final LinearLayout tab = new LinearLayout(context);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setClickable(true);
            tab.setFocusable(true);
            tab.setOnClickListener(v -> pick.accept(id));
            final LinearLayout label = new LinearLayout(context);
            label.setGravity(Gravity.CENTER);
            label.setMinimumHeight(Math.round(41 * density));
            label.setPadding(Math.round(9 * density), 0, Math.round(9 * density), 0);
            final TextView name = new TextView(context);
            name.setText(requestsTab ? R.string.velo_tab_requests : NAMES[i]);
            name.setTextSize(14);
            name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
            name.setTextColor(selected ? primary : muted);
            label.addView(name);
            if (count > 0) {
                final TextView badge = new TextView(context);
                badge.setText(String.valueOf(count));
                badge.setTextSize(12);
                badge.setTextColor(onPrimary);
                badge.setPadding(Math.round(7 * density), 0, Math.round(7 * density), 0);
                final GradientDrawable pill = new GradientDrawable();
                pill.setCornerRadius(99 * density);
                pill.setColor(primary);
                badge.setBackground(pill);
                final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
                params.setMarginStart(Math.round(5 * density));
                label.addView(badge, params);
            }
            tab.addView(label, new LinearLayout.LayoutParams(-2, -2));
            final View line = new View(context);
            line.setBackgroundColor(selected ? primary : 0);
            tab.addView(line, new LinearLayout.LayoutParams(-1, Math.round(3 * density)));
            row.addView(tab, new LinearLayout.LayoutParams(-2, -2));
        }
    }
}
