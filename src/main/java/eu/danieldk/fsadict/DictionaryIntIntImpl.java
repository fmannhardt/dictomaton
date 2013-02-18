// Copyright 2013 Daniel de Kok
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package eu.danieldk.fsadict;

import eu.danieldk.fsadict.Dictionary;

import java.io.Serializable;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Stack;

/**
 * A finite state dictionary. Dictionaries of this type can are constructed
 * using {@link eu.danieldk.fsadict.DictionaryBuilder#build()}.
 *
 * This class uses integers (int) for transition and state numbers.
 *
 * @author Daniel de Kok
 */
class DictionaryIntIntImpl implements Dictionary {
	private static final long serialVersionUID = 3199608511519213621L;

	// Offset in the transition table of the given state. E.g. d_stateOffsets[3] = 10
	// means that state 3 starts at index 10 in the transition table.
	protected final int[] d_stateOffsets;

	// Note: we do not use an array of transition instances to represent the
	//       transition table, since this would require an additional pointer
	//       for each transition. Instead, we maintain the table as two parallel
	//       arrays.

	protected final char[] d_transitionChars;
	protected final int[] d_transtitionTo;
	protected final Set<Integer> d_finalStates;

	/**
	 * Check whether the dictionary contains the given sequence.
	 * @param seq
	 * @return
	 */
	public boolean contains(CharSequence seq)
	{
		int state = 0;
		for (int i = 0; i < seq.length(); i++)
		{
			state = next(state, seq.charAt(i));

			if (state == -1)
				return false;
		}

		return d_finalStates.contains(state);
	}

	/**
	 * Get an iterator over the character sequences in the dictionary.
	 */
	@Override
	public Iterator<CharSequence> iterator() {
		return new DictionaryIterator();
	}

    /**
     * Get the number of sequences in the automaton. This method is slow for
     * {@link Dictionary} instances, since it needs to traverse the automaton.
     *
     * @return Number of sequences.
     */
    public int size()
    {
        int nSeqs = 0;
        Iterator<CharSequence> iter = iterator();
        while (iter.hasNext())
        {
            ++nSeqs;
            iter.next();
        }

        return nSeqs;
    }

	/**
	 * Give the Graphviz dot representation of this automaton.
	 * @return
	 */
	public String toDot()
	{
		StringBuilder dotBuilder = new StringBuilder();

		dotBuilder.append("digraph G {\n");

		for (int state = 0; state < d_stateOffsets.length; ++state)
		{
			for (int trans = d_stateOffsets[state]; trans < transitionsUpperBound(state); ++trans)
				dotBuilder.append(String.format("%d -> %d [label=\"%c\"]\n",
						state, d_transtitionTo[trans], d_transitionChars[trans]));

			if (d_finalStates.contains(state))
				dotBuilder.append(String.format("%d [peripheries=2];\n", state));
		}

		dotBuilder.append("}");

		return dotBuilder.toString();
	}

	private class DictionaryIterator implements Iterator<CharSequence>
	{
		private final Stack<StateStringPair> d_stack;
		private String d_nextSeq;

		public DictionaryIterator()
		{
			d_stack = new Stack<StateStringPair>();
			d_stack.push(new StateStringPair(0, ""));
			d_nextSeq = null;
		}

		@Override
		public boolean hasNext() {
			StateStringPair pair;
			while (d_stack.size() != 0)
			{
				pair = d_stack.pop();
				int state = pair.getState();
				String string = pair.getString();

				// Put states reachable through outgoing transitions on the stack.
				for (int trans = transitionsUpperBound(state) - 1; trans >= d_stateOffsets[state]; --trans)
					d_stack.push(new StateStringPair(d_transtitionTo[trans], string + d_transitionChars[trans]));

				if (d_finalStates.contains(state))
				{
					d_nextSeq = string;
					return true;
				}
			}

			return false;
		}

		@Override
		public CharSequence next() {
			if (d_nextSeq == null)
				throw new NoSuchElementException();

			return d_nextSeq;
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException();
		}
	}

	private class StateStringPair
	{
		private final int d_state;
		private final String d_string;

		public StateStringPair(int state, String string)
		{
			d_state = state;
			d_string = string;
		}

		public int getState() {
			return d_state;
		}

		public String getString() {
			return d_string;
		}

	}

	/**
	 * Construct a dictionary.
	 *
	 * @param stateOffsets Per-state offset in the transition table.
	 * @param transitionChars Transition table (characters).
	 * @param transitionTo Transition table (to-transitions).
	 * @param finalStates Set of final states.
	 */
	protected DictionaryIntIntImpl(int[] stateOffsets, char[] transitionChars,
                                   int[] transitionTo, Set<Integer> finalStates)
	{
		d_stateOffsets = stateOffsets;
		d_transitionChars = transitionChars;
		d_transtitionTo = transitionTo;
		d_finalStates = finalStates;
	}

	/**
	 * Calculate the upper bound for this state in the transition table.
	 *
	 * @param state
	 * @return
	 */
	protected int transitionsUpperBound(int state)
	{
		return state + 1 < d_stateOffsets.length ? d_stateOffsets[state + 1] :
			d_transitionChars.length;
	}

	/**
	 * Find the transition for the given character in the given state. Since the
	 * transitions are ordered by character, we can use a binary search.
	 *
	 * @param state
	 * @param c
	 * @return
	 */
	protected int findTransition(int state, char c)
	{
		int start = d_stateOffsets[state];
		int end = transitionsUpperBound(state) - 1;

		// Binary search
		while (end >= start)
		{
			int mid = start + ((end - start) / 2);

			if (d_transitionChars[mid] > c)
				end = mid - 1;
			else if (d_transitionChars[mid] < c)
				start = mid + 1;
			else
				return mid;
		}

		return -1;
	}

	/**
	 * Get the next state, given a character.
	 *
	 * @param state
	 * @param c
	 * @return
	 */
	private int next(int state, char c)
	{
		int trans = findTransition(state, c);

		if (trans == -1)
			return -1;

		return d_transtitionTo[trans];
	}

}