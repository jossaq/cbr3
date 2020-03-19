/**
 * Copyright (c) 2011, University of Konstanz, Distributed Systems Group All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met: * Redistributions of source code must retain the
 * above copyright notice, this list of conditions and the following disclaimer. * Redistributions
 * in binary form must reproduce the above copyright notice, this list of conditions and the
 * following disclaimer in the documentation and/or other materials provided with the distribution.
 * * Neither the name of the University of Konstanz nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.sirix.axis.concurrent;

import org.sirix.api.Axis;
import org.sirix.api.NodeCursor;
import org.sirix.api.NodeReadOnlyTrx;
import org.sirix.axis.AbstractAxis;
import org.sirix.settings.Fixed;

/**
 * <h1>ConcurrentExceptAxis</h1>
 * <p>
 * Computes concurrently and returns the nodes of the first operand except those of the second
 * operand. This axis takes two node sequences as operands and returns a sequence containing all the
 * nodes that occur in the first, but not in the second operand. Document order is preserved.
 * </p>
 */
public final class ConcurrentExceptAxis<R extends NodeCursor & NodeReadOnlyTrx> extends AbstractAxis {

  /** First operand sequence. */
  private final ConcurrentAxis<R> mOp1;

  /** Second operand sequence. */
  private final ConcurrentAxis<R> mOp2;

  /** Is axis called for the first time? */
  private boolean mFirst;

  /** Current result of the 1st axis */
  private long mCurrentResult1;

  /** Current result of the 2nd axis. */
  private long mCurrentResult2;

  /**
   * Constructor. Initializes the internal state.
   *
   * @param rtx exclusive (immutable) trx to iterate with
   * @param operand1 first operand
   * @param operand2 second operand
   * @throws NullPointerException if {@code rtx}, {@code operand1} or {@code operand2} is {@code null}
   */
  public ConcurrentExceptAxis(final R rtx, final Axis operand1, final Axis operand2) {
    super(rtx);
    mOp1 = new ConcurrentAxis<>(rtx, operand1);
    mOp2 = new ConcurrentAxis<>(rtx, operand2);
    mFirst = true;
    mCurrentResult1 = Fixed.NULL_NODE_KEY.getStandardProperty();
    mCurrentResult2 = Fixed.NULL_NODE_KEY.getStandardProperty();
  }

  @Override
  public void reset(final long pNodeKey) {
    super.reset(pNodeKey);

    if (mOp1 != null) {
      mOp1.reset(pNodeKey);
    }
    if (mOp2 != null) {
      mOp2.reset(pNodeKey);
    }

    mFirst = true;
    mCurrentResult1 = Fixed.NULL_NODE_KEY.getStandardProperty();
    mCurrentResult2 = Fixed.NULL_NODE_KEY.getStandardProperty();
  }

  @Override
  protected long nextKey() {
    if (mFirst) {
      mFirst = false;
      mCurrentResult1 = Util.getNext(mOp1);
      mCurrentResult2 = Util.getNext(mOp2);
    }

    final long nodeKey;

    // if 1st axis has a result left that is not contained in the 2nd it is
    // returned
    while (!mOp1.isFinished()) {
      while (!mOp2.isFinished()) {
        if (mOp1.isFinished())
          break;

        while (mCurrentResult1 >= mCurrentResult2 && !mOp1.isFinished() && !mOp2.isFinished()) {

          // don't return if equal
          while (mCurrentResult1 == mCurrentResult2 && !mOp1.isFinished() && !mOp2.isFinished()) {
            mCurrentResult1 = Util.getNext(mOp1);
            mCurrentResult2 = Util.getNext(mOp2);
          }

          // a1 has to be smaller than a2 to check for equality
          while (mCurrentResult1 > mCurrentResult2 && !mOp1.isFinished() && !mOp2.isFinished()) {
            mCurrentResult2 = Util.getNext(mOp2);
          }
        }

        if (!mOp1.isFinished() && !mOp2.isFinished()) {
          // as long as a1 is smaller than a2 it can be returned
          assert (mCurrentResult1 < mCurrentResult2);
          nodeKey = mCurrentResult1;
          if (Util.isValid(nodeKey)) {
            mCurrentResult1 = Util.getNext(mOp1);
            return nodeKey;
          }
          // should never come here!
          throw new IllegalStateException(nodeKey + " is not valid!");
        }
      }

      if (!mOp1.isFinished()) {
        // only operand1 has results left, so return all of them
        nodeKey = mCurrentResult1;
        if (Util.isValid(nodeKey)) {
          mCurrentResult1 = Util.getNext(mOp1);
          return nodeKey;
        }
        // should never come here!
        throw new IllegalStateException(nodeKey + " is not valid!");
      }
    }

    return done();
  }
}
