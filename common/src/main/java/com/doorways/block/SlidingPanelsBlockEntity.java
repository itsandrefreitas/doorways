package com.doorways.block;

import com.doorways.core.geometry.Swing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * Where a sliding panel is, between the two places its blockstate can describe.
 *
 * <p>This is the first block entity in the mod, and it is worth saying plainly what it does and
 * does not do. It holds <b>no</b> state the game depends on: every part still reconstructs the
 * whole door from its own blockstate (§3), and a door whose block entity vanished would keep
 * working -- it would simply stop gliding and start snapping. What it exists for is drawing. A
 * block's model is chosen by its state, and a state is a discrete thing with no room for a
 * position halfway between two of them.
 *
 * <h2>Nothing is synchronised, and nothing is saved</h2>
 * There is no packet here and no disk write. The renderer asks where the panel is, and this
 * class notices when the answer the blockstate gives has changed since it last looked -- which
 * on the client happens as the block update arrives. Each side works it out from what it had.
 *
 * <p>A block entity adopts whatever position its state describes at birth, so a door that was
 * already open when the chunk loaded is drawn open rather than sliding open in front of you.
 *
 * <h2>One door, one departure</h2>
 * Each panel keeps its own start, finish and clock, but they all take the same <b>moment of
 * departure</b> from the door's anchor: the first to notice sets it, the rest are handed it.
 *
 * <p>Sharing the running clock instead was tried and was worse. A panel that had not yet
 * noticed the change went on interpolating its previous journey against a stopwatch that had
 * already been restarted, so for a tick it slid backwards. Sharing only the instant leaves a
 * panel that knows nothing sitting exactly where it is, which is what it should do.
 *
 * <p>What this fixes is a real desync, not a theoretical one: a block entity starts moving when
 * it <b>notices</b>, and the block the player clicked hears about it before the rest, through
 * the acknowledgement of the click itself. The halves of one column came a tick apart and the
 * door visibly split.
 */
public class SlidingPanelsBlockEntity extends BlockEntity {

    /**
     * How long a panel takes to travel, in ticks.
     *
     * <p>Taken from the block rather than written again here. The two have to agree exactly --
     * the server hands the drawing back {@code SLIDE_TICKS + 2} after it set off -- and a copy
     * is a copy that can drift.
     */
    private static final float SLIDE_TICKS = WideDoorBlock.SLIDE_TICKS;

    /**
     * How long this keeps drawing after the block model has taken the job back.
     *
     * <p>A chunk's mesh is rebuilt off the render thread, one to three frames after the state
     * that invalidated it. On departure that costs nothing: the stale mesh still holds the panel
     * where it is, and the panel has barely left. On <b>arrival</b> it left a hole -- the flag
     * cleared, this stopped drawing in the same frame, and the mesh that was to replace it was
     * still the old one, which drew nothing at all. The door blinked out for two or three frames
     * at the end of every slide.
     *
     * <p>Three ticks of overlap close it. The overlap costs nothing visually because what is
     * drawn during it is the same panel, from the same state, in the same place the mesh is
     * about to draw it -- this only stops once the mesh has certainly caught up.
     */
    private static final int SETTLE_TICKS = 3;

    /** The name the painting is stored under. */
    private static final String PATTERN_KEY = "pattern";

    /**
     * The painting on this panel, or null for bare paper.
     *
     * <p>The first thing this class has ever held that the world depends on. Everything else
     * here is derived from the blockstate and can be thrown away without loss; this cannot, so
     * it is saved to disk and sent to clients, and the two paths below exist for it alone.
     *
     * <p>It is kept on <b>every</b> panel of the leaf rather than on an anchor. A painting spans
     * a whole leaf and each block draws its own quarter of it, so each block needs the answer
     * anyway -- and asking a neighbour for it would be a lookup per block per frame.
     */
    private @Nullable DoorPattern pattern;

    /** Where the panel set off from, in columns along the wall. */
    private float from;

    /** Where it is heading. Compared against the blockstate to spot a change. */
    private float target;

    /** When this panel set off. Taken from the anchor, so a whole door leaves together. */
    private long startedAt = Long.MIN_VALUE / 2;

    /**
     * When the <b>door</b> last set off, and on which journey. Only the anchor's copy is read.
     *
     * <p>Deliberately not the same field as {@link #startedAt}, though it usually holds the same
     * number. The anchor is a panel as well as a clock, and the two roles disagree for exactly
     * one tick: when another panel notices a reversal first, the door's departure is rewritten
     * while the anchor is still interpolating the previous journey. Sharing one field meant the
     * anchor read a stopwatch that had just been reset and jumped back to where its last journey
     * began -- a small teleport, only on a reversal, and only sometimes.
     */
    private long departedAt = Long.MIN_VALUE / 2;

    /** Which journey the clock is timing, so that a reversal restarts it and a latecomer joins. */
    private Swing journey = Swing.CLOSED;

    /** When the panel stopped, or {@link Long#MAX_VALUE} while it has not. */
    private long stoppedAt = Long.MIN_VALUE / 2;

    /**
     * When the door set off, as the <b>server</b> counts it. Never read by the drawing.
     *
     * <p>The server has no clock of its own for a slide: it sets the flag, schedules the tick
     * that will clear it, and forgets. That breaks as soon as a door is told to reverse, because
     * the tick from the first journey is still in the queue and comes due in the middle of the
     * second. This is what lets that tick recognise itself as stale.
     */
    private long departedAtServer = Long.MIN_VALUE / 2;

    /** Notes a departure, from the server's tick loop. */
    public void departed(long now) {
        departedAtServer = now;
    }

    /** How many ticks are left of the journey that is under way, or 0 if it is due. */
    public int remainingSlide(long now) {
        long elapsed = now - departedAtServer;
        return elapsed >= WideDoorBlock.SLIDE_TICKS || elapsed < 0
                ? 0
                : (int) (WideDoorBlock.SLIDE_TICKS - elapsed);
    }

    public SlidingPanelsBlockEntity(BlockPos pos, BlockState state) {
        super(DoorwaysContent.slidingPanels(), pos, state);
        // Adopt the position the state already describes, here rather than on the first frame
        // that asks. The renderer only asks while a panel is travelling, so a door that had
        // never travelled was still holding its default when it first opened -- it took the
        // open position for its starting point and arrived without going anywhere.
        from = restingOffset(state);
        target = from;
    }

    /** The painting on this panel, or null. */
    public @Nullable DoorPattern pattern() {
        return pattern;
    }

    /** Puts a painting on this panel, or takes it off. The caller announces the change. */
    public void setPattern(@Nullable DoorPattern pattern) {
        this.pattern = pattern;
        setChanged();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (pattern != null) {
            output.putString(PATTERN_KEY, pattern.id());
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        // An unknown name becomes no painting rather than a crash: a world saved with a mod
        // version that had a pattern this one does not know still opens, and the door is bare.
        pattern = input.getString(PATTERN_KEY).map(DoorPattern::byId).orElse(null);
    }

    /**
     * The two halves of telling the client. {@code getUpdateTag} is what a chunk carries when it
     * is sent whole; {@code getUpdatePacket} is what a single block change carries afterwards.
     * Both are needed -- with only the second, a door already painted when you arrive is bare
     * until someone touches it.
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private static float restingOffset(BlockState state) {
        return state.getBlock() instanceof WideDoorBlock door ? door.panelOffset(state) : 0.0F;
    }

    /**
     * Where this panel is right now, in columns along the wall.
     *
     * <p>{@code partialTick} is the fraction of a tick the frame is being drawn at, which is
     * what makes this smooth rather than merely stepped: the renderer asks many times per tick
     * and gets a different answer each time.
     */
    public float panelOffset(float partialTick) {
        if (level == null || !(getBlockState().getBlock() instanceof WideDoorBlock door)) {
            return 0.0F;
        }

        float wanted = door.panelOffset(getBlockState());
        if (wanted != target) {
            // Set off from wherever this panel is now -- read on its own clock, which is still
            // the old one at this point. That is what makes an interrupted slide reverse
            // smoothly instead of jumping back to where it started.
            from = positionAt(partialTick);
            target = wanted;
            startedAt = clock(door).depart(level.getGameTime(),
                    WideDoorBlock.swingOf(getBlockState()));
        }
        return positionAt(partialTick);
    }

    /**
     * Whether the renderer draws this panel, rather than the block model.
     *
     * <p>True while it travels, and for {@link #SETTLE_TICKS} after it stops, which is what
     * covers the chunk mesh being rebuilt behind it.
     *
     * <p>It records as it answers: there is no tick on this block entity and nothing tells it
     * the panel arrived, so the first frame to find the flag gone is what dates the arrival.
     */
    public boolean isDrawnHere() {
        if (level == null || !(getBlockState().getBlock() instanceof WideDoorBlock door)) {
            return false;
        }
        // A see-through door never hands its drawing back at all, so there is nothing here to
        // wait for or to cover. See DoorStyle.drawnByRenderer().
        if (door.style().drawnByRenderer()) {
            return true;
        }
        if (door.isMoving(getBlockState())) {
            stoppedAt = Long.MAX_VALUE;
            return true;
        }
        if (stoppedAt == Long.MAX_VALUE) {
            stoppedAt = level.getGameTime();
        }
        return level.getGameTime() - stoppedAt < SETTLE_TICKS;
    }

    /**
     * The block entity that keeps this door's moment of departure: the one at the anchor, which
     * may well be this one.
     *
     * <p>Falls back to itself if the anchor is not loaded. A door straddling the edge of what
     * the client has can then come apart, which is a better answer than not moving at all.
     */
    private SlidingPanelsBlockEntity clock(WideDoorBlock door) {
        BlockPos anchor = door.anchorOf(getBlockState(), getBlockPos());
        return level != null && level.getBlockEntity(anchor)
                instanceof SlidingPanelsBlockEntity found ? found : this;
    }

    /**
     * The moment this door set off, starting it if nothing is under way.
     *
     * <p>The first panel to notice a change sets the time; every later one is given the same
     * one back, and so catches up to where the door already is rather than starting afresh.
     *
     * <p>A door told to reverse mid-slide is a new journey, not a latecomer to the old one, and
     * the swing is what tells the two apart. Without that test, closing a door three ticks into
     * opening it handed the panels a clock that was already half spent, and they jumped most of
     * the way back in a single frame.
     */
    private long depart(long now, Swing swing) {
        if (swing != journey || now - departedAt >= SLIDE_TICKS) {
            journey = swing;
            departedAt = now;
        }
        return departedAt;
    }

    private float positionAt(float partialTick) {
        if (level == null) {
            return target;
        }
        float elapsed = level.getGameTime() - startedAt + partialTick;
        if (elapsed >= SLIDE_TICKS || elapsed < 0.0F) {
            return target;
        }
        return Mth.lerp(elapsed / SLIDE_TICKS, from, target);
    }
}
