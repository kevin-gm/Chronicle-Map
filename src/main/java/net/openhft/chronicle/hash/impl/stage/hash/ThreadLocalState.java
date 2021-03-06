/*
 *      Copyright (C) 2012, 2016  higherfrequencytrading.com
 *      Copyright (C) 2016 Roman Leventov
 *
 *      This program is free software: you can redistribute it and/or modify
 *      it under the terms of the GNU Lesser General Public License as published by
 *      the Free Software Foundation, either version 3 of the License.
 *
 *      This program is distributed in the hope that it will be useful,
 *      but WITHOUT ANY WARRANTY; without even the implied warranty of
 *      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *      GNU Lesser General Public License for more details.
 *
 *      You should have received a copy of the GNU Lesser General Public License
 *      along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.openhft.chronicle.hash.impl.stage.hash;

import net.openhft.chronicle.core.Memory;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.hash.ChronicleHash;
import net.openhft.chronicle.hash.ChronicleHashClosedException;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static net.openhft.chronicle.hash.impl.BigSegmentHeader.LOCK_TIMEOUT_SECONDS;

public abstract class ThreadLocalState {
    public boolean iterationContextLockedInThisThread;

    private static final Memory MEMORY = OS.memory();
    private static final long CONTEXT_LOCK_OFFSET;
    private static final int CONTEXT_UNLOCKED = 0;
    private static final int CONTEXT_LOCKED_LOCALLY = 1;
    private static final int CONTEXT_CLOSED = 2;

    static {
        try {
            Field contextLockField =
                    ThreadLocalState.class.getDeclaredField("contextLock");
            contextLockField.setAccessible(true);
            CONTEXT_LOCK_OFFSET = MEMORY.getFieldOffset(contextLockField);
        } catch (NoSuchFieldException | SecurityException e) {
            throw new AssertionError(e);
        }
    }

    private volatile int contextLock = CONTEXT_UNLOCKED;

    /**
     * Returns {@code true} if this is the outer context lock in this thread, {@code false} if this
     * is a nested context.
     */
    public boolean lockContextLocally() {
        // hash().isOpen() check guarantees no starvation of a thread calling chMap.close() and
        // trying to close this context by closeContext() method below, while the thread owning this
        // context frequently locks and unlocks it (e. g. in a loop). This is also the only check
        // for chMap openness during the whole context usage lifecycle.
        if (hash().isOpen() && MEMORY.compareAndSwapInt(this, CONTEXT_LOCK_OFFSET,
                CONTEXT_UNLOCKED, CONTEXT_LOCKED_LOCALLY)) {
            return true;
        } else {
            if (contextLock == CONTEXT_LOCKED_LOCALLY)
                return false;
            // Don't extract this hash().isOpen() and the one above, because they could different
            // results (the first (above) could return true, the second (below) - false).
            if (!hash().isOpen())
                throw new ChronicleHashClosedException();
            throw new AssertionError("Unknown context lock state: " + contextLock);
        }
    }

    public void unlockContextLocally() {
        // Ensure all reads from mapped memory are done before thread calling chronicleMap.close()
        // frees resources potentially nulling some pointer used in those reads.
        MEMORY.loadFence();
        // Avoid volatile write to avoid expensive store-load barrier
        MEMORY.writeOrderedInt(this, CONTEXT_LOCK_OFFSET, CONTEXT_UNLOCKED);
    }

    public void closeContext() {
        if (tryCloseContext())
            return;
        if (owner() == Thread.currentThread())
            throw new IllegalStateException("Attempt to close a Chronicle Hash in the context " +
                    "of not yet finished query or iteration");
        // Double the current timeout for segment locks "without timeout", that effectively
        // specifies maximum lock (hence context) holding time
        long timeoutMillis = TimeUnit.SECONDS.toMillis(LOCK_TIMEOUT_SECONDS) * 2;
        long lastTime = System.currentTimeMillis();
        do {
            if (tryCloseContext())
                return;
            Thread.yield();
            long now = System.currentTimeMillis();
            if (now != lastTime) {
                lastTime = now;
                timeoutMillis--;
            }
        } while (timeoutMillis >= 0);
        throw new RuntimeException("Failed to close a context, belonging to the thread\n" +
                owner() + ", in the state: " + owner().getState() + ", current stack trace:\n" +
                Arrays.toString(owner().getStackTrace()) + "\n" +
                "Possible reasons:\n" +
                "- The context owner thread exited before closing this context. Ensure that you\n" +
                "always close opened Chronicle Map's contexts, the best way to do this is to use\n" +
                "try-with-resources blocks." +
                "- The context owner thread runs some context operation (e. g. a query) for\n" +
                "unexpectedly long time (at least " + LOCK_TIMEOUT_SECONDS + " seconds).\n" +
                "You should either redesign your logic to spend less time in Chronicle Map\n" +
                "contexts (recommended) or synchronize map.close() with queries externally,\n" +
                "so that close() is called only after all query operations finished.\n" +
                "- Iteration over a large Chronicle Map takes more than " + LOCK_TIMEOUT_SECONDS +
                " seconds.\n" +
                "In this case you should synchronize map.close() with iterations over the map\n" +
                "externally, so that close() is called only after all iterations are finished.\n" +
                "- This is a dead lock involving the context owner thread and this thread (from\n" +
                "which map.close() method is called. Make sure you always close Chronicle Map\n" +
                "contexts, preferably using try-with-resources blocks.");
    }

    private boolean tryCloseContext() {
        return MEMORY.compareAndSwapInt(this, CONTEXT_LOCK_OFFSET,
                CONTEXT_UNLOCKED, CONTEXT_CLOSED);
    }

    public abstract Thread owner();

    public abstract ChronicleHash<?, ?, ?, ?> hash();
}
