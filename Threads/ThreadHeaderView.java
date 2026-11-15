package org.telegram.ui.threads;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.view.Gravity;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import java.util.ArrayList;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeBind;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;

// THREADS: title block of the thread screen header (title + participants' avatars + chat name), rebuilt after ChatAvatarContainer in glass
// mode without an avatar. Sits inside the ActionBar like the chat's avatar container (margins 52 / 52) and draws the
// center glass pill itself, hugging the text and centered, as ActionBar does for the chat's avatar container.
public class ThreadHeaderView extends FrameLayout {

    private final SimpleTextView titleTextView;
    private final SimpleTextView subtitleTextView;
    private final BlurredBackgroundDrawable glassDrawable;
    private boolean occupyStatusBar = true;

    // THREADS (stage 5): participants before the chat name, Slack-style. Same rule as the thread bar under the message
    // (ChatMessageCell): up to 3 people, 30% overlap, a 2dp gap, the leftmost on top; 20dp here.
    private static final float AVATAR_SIZE = 20;
    private static final float AVATAR_STEP = AVATAR_SIZE * 0.7f;
    private static final float AVATAR_GAP = 2;
    // THREADS: the channel comment bar puts its text 11dp after the last avatar (ChatMessageCell), same gap here
    private static final float AVATARS_TEXT_GAP = 11;
    private static final int MAX_AVATARS = 3;
    private final ImageReceiver[] avatarImages = new ImageReceiver[MAX_AVATARS];
    private final AvatarDrawable[] avatarDrawables = new AvatarDrawable[MAX_AVATARS];
    private int avatarsCount;
    // THREADS: as AvatarsDrawable: each avatar cuts a transparent gap out of the one to its right (over glass)
    private final Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ThreadHeaderView(Context context, BlurredBackgroundDrawableViewFactory factory,
                            BlurredBackgroundColorProvider colorProvider, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        setWillNotDraw(false);
        setClipChildren(false);

        // THREADS: same pill as ActionBar.setupGlass(): radius 23, padding 6.
        glassDrawable = factory.create(this, colorProvider);
        glassDrawable.setRadius(dp(23));
        glassDrawable.setPadding(dp(6));

        // THREADS: same text setup as ChatAvatarContainer (+ setGlassMode(): 17.5 / 13.5).
        titleTextView = new SimpleTextView(context);
        titleTextView.setEllipsizeByGradient(true);
        ThemeBind.textColor(titleTextView, Theme.key_actionBarDefaultTitle, resourcesProvider);
        titleTextView.setTextSize(18);
        titleTextView.setTextSizePx(dp(17.5f));
        titleTextView.setGravity(Gravity.LEFT);
        titleTextView.setTypeface(AndroidUtilities.bold());
        titleTextView.setPadding(0, dp(6), 0, dp(12));
        addView(titleTextView);

        subtitleTextView = new SimpleTextView(context);
        subtitleTextView.setEllipsizeByGradient(true);
        ThemeBind.textColor(subtitleTextView, Theme.key_actionBarDefaultSubtitle, resourcesProvider);
        subtitleTextView.setTextSize(14);
        subtitleTextView.setTextSizePx(dp(13.5f));
        subtitleTextView.setGravity(Gravity.LEFT);
        subtitleTextView.setPadding(0, 0, dp(10), 0);
        addView(subtitleTextView);

        // THREADS (stage 5)
        for (int i = 0; i < MAX_AVATARS; i++) {
            avatarImages[i] = new ImageReceiver(this);
            avatarImages[i].setRoundRadius(dp(AVATAR_SIZE) / 2);
            avatarDrawables[i] = new AvatarDrawable();
            avatarDrawables[i].setTextSize(dp(AVATAR_SIZE * 0.5f));
        }
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
    }

    // THREADS (stage 5): the thread's participants (ThreadsStore.getParticipants), most recent first.
    public void setParticipants(int account, ArrayList<Long> participants) {
        final MessagesController controller = MessagesController.getInstance(account);
        avatarsCount = 0;
        for (int i = 0; i < participants.size() && avatarsCount < MAX_AVATARS; i++) {
            final TLRPC.User user = controller.getUser(participants.get(i));
            if (user == null) {
                continue;
            }
            avatarDrawables[avatarsCount].setInfo(account, user);
            avatarImages[avatarsCount].setForUserOrChat(user, avatarDrawables[avatarsCount]);
            avatarsCount++;
        }
        requestLayout();
        invalidate();
    }

    // THREADS (stage 5): width of the avatar row plus its gap before the chat name.
    private int avatarsWidth() {
        if (avatarsCount == 0) {
            return 0;
        }
        return (int) (dp(AVATAR_SIZE) + dp(AVATAR_STEP) * (avatarsCount - 1) + dp(AVATARS_TEXT_GAP));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        for (ImageReceiver imageReceiver : avatarImages) {
            imageReceiver.onAttachedToWindow(); // THREADS
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        for (ImageReceiver imageReceiver : avatarImages) {
            imageReceiver.onDetachedFromWindow(); // THREADS
        }
    }

    public void setOccupyStatusBar(boolean occupy) {
        occupyStatusBar = occupy;
        requestLayout();
    }

    public void setTitle(CharSequence title) {
        titleTextView.setText(title);
        requestLayout();
        invalidate();
    }

    public void setSubtitle(CharSequence subtitle) {
        subtitleTextView.setText(subtitle);
        requestLayout();
        invalidate();
    }

    // THREADS: ChatAvatarContainer.getVisualWidth() without avatar.
    private int getVisualWidth() {
        final float width = Math.max(titleTextView.getExactWidthIncludeDrawables(), avatarsWidth() + subtitleTextView.getExactWidthIncludeDrawables()); // THREADS: + avatars
        return (int) width + dp(34);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        // THREADS: ChatAvatarContainer.onMeasure() without avatar; the pill is p * 2 wider than the text block.
        final int availableWidth = width - dp(16) - dp(6) * 2;
        titleTextView.measure(MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(dp(24 + 8), MeasureSpec.AT_MOST));
        subtitleTextView.measure(MeasureSpec.makeMeasureSpec(Math.max(0, availableWidth - avatarsWidth()) /* THREADS */, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(dp(20), MeasureSpec.AT_MOST));
        setMeasuredDimension(width, MeasureSpec.getSize(heightMeasureSpec));
    }

    private int pillLeft() {
        final int p = dp(6);
        final int width = Math.min(getMeasuredWidth(), getVisualWidth() + p * 2);
        return (getMeasuredWidth() - width) / 2;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        // THREADS: ChatAvatarContainer.onLayout() in glass mode (avatar 42dp, hidden); x follows ActionBar's
        // translation of the avatar container: pill left + p + 3dp, then leftPadding 8 + 13 inside it.
        final int actionBarHeight = ActionBar.getCurrentActionBarHeight();
        final int viewTop = (actionBarHeight - (dp(42) - 2) - 2) / 2 + (occupyStatusBar ? AndroidUtilities.statusBarHeight : 0);
        final int subtitleTop = viewTop + dp(23.66f);
        final int l = pillLeft() + dp(6) + dp(3) + dp(13);
        titleTextView.layout(l, viewTop + dp(1.66f) - titleTextView.getPaddingTop(), l + titleTextView.getMeasuredWidth(),
            viewTop + titleTextView.getTextHeight() + dp(1.66f) - titleTextView.getPaddingTop() + titleTextView.getPaddingBottom());
        // THREADS (stage 5): the avatar row first, centered on the chat name's line
        final int sl = l + avatarsWidth();
        subtitleTextView.layout(sl, subtitleTop, sl + subtitleTextView.getMeasuredWidth(), subtitleTop + subtitleTextView.getTextHeight());
        final float cy = subtitleTop + subtitleTextView.getTextHeight() / 2f;
        for (int i = 0; i < avatarsCount; i++) {
            avatarImages[i].setImageCoords(l + dp(AVATAR_STEP) * i, cy - dp(AVATAR_SIZE) / 2f, dp(AVATAR_SIZE), dp(AVATAR_SIZE));
        }
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        // THREADS: same vertical bounds as ActionBar.dispatchDraw() glass pills.
        final int p = dp(6);
        final int s = dp(46);
        final int t = getHeight() - (ActionBar.getCurrentActionBarHeight() + s) / 2 - p;
        final int b = t + s + p * 2;
        final int left = pillLeft();
        final int width = Math.min(getMeasuredWidth(), getVisualWidth() + p * 2);
        glassDrawable.setBounds(left, t, left + width, b);
        glassDrawable.draw(canvas);
        super.dispatchDraw(canvas);
        drawAvatars(canvas); // THREADS (stage 5)
    }

    // THREADS (stage 5): right to left, so each avatar lies over the one to its right, with a transparent gap
    // cut around it (AvatarsDrawable does the same over transparent backgrounds).
    private void drawAvatars(Canvas canvas) {
        if (avatarsCount == 0) {
            return;
        }
        final float l = avatarImages[0].getImageX() - dp(AVATAR_GAP);
        final float t = avatarImages[0].getImageY() - dp(AVATAR_GAP);
        final float r = avatarImages[avatarsCount - 1].getImageX2() + dp(AVATAR_GAP);
        final float b = avatarImages[0].getImageY2() + dp(AVATAR_GAP);
        canvas.saveLayer(l, t, r, b, null);
        for (int i = avatarsCount - 1; i >= 0; i--) {
            if (i < avatarsCount - 1) {
                canvas.drawCircle(avatarImages[i].getCenterX(), avatarImages[i].getCenterY(), dp(AVATAR_SIZE) / 2f + dp(AVATAR_GAP), clearPaint);
            }
            avatarImages[i].draw(canvas);
        }
        canvas.restore();
    }

    public void updateColors() {
        glassDrawable.updateColors();
        invalidate();
    }
}
