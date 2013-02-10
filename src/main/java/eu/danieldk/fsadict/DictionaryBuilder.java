// Copyright 2013 Daniël de Kok
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

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;

/**
 * This class is used to construct a dictionary in the form of a minimized
 * deterministic finite state automaton. This builder can create a normal
 * dictionary automaton ({@link Dictionary} or a perfect hash automaton
 * ({@link PerfectHashDictionary}).
 * <p/>
 * The workflow is simple:
 * 
 * <ul>
 * <li>Create an instance of this class.</li>
 * <li>Add character sequences in lexicographic order using {@link DictionaryBuilder#add(String)}.</li>
 * <li>Construct the automaton with {@link DictionaryBuilder#build()} or
 * 		{@link DictionaryBuilder#buildPerfectHash()}.</li>
 * </ul>
 * 
 * Construction of the automaton finalizes the build process - it is not possible to add
 * new character sequences afterwards.
 * <p/>
 * The following construction algorithm is used:
 * </p>
 * <i>Incremental Construction of Minimal Acyclic Finite-State Automata</i>, Jan Daciuk, Bruce W. Watson,
 * Stoyan Mihov, and Robert E. Watson, 2000, Association for Computational Linguistics
 * 
 * @author Daniël de Kok
 */
public class DictionaryBuilder {
	private final State d_startState;
	private final Map<State, State> d_register;
	private String d_prevSeq;
	private boolean d_finalized;
	
	/**
	 * Construct a {@link DictionaryBuilder}.
	 */
	public DictionaryBuilder()
	{
		d_startState = new State();
		d_register = new HashMap<State, State>();
		d_finalized = false;
	}
	
	/**
	 * Add a character sequence.
	 * @param seq
	 */
	public void add(String seq) throws DictionaryBuilderException
	{
		if (d_finalized)
			throw new DictionaryBuilderException("Cannot add a sequence to a finalized DictionaryBuilder.");
	
		if (d_prevSeq != null && d_prevSeq.compareTo(seq) >= 0)
			throw new DictionaryBuilderException(String.format("Sequences are not added in lexicographic order: %s %s", d_prevSeq, seq));
		
		d_prevSeq = seq;
		
		// Traverse across the shared prefix.
		int i = 0;
		State curState = d_startState;
		for (; i < seq.length(); i++)
		{
			State nextState = curState.move(seq.charAt(i));
			if (nextState != null)
				curState = nextState;
			else
				break;
		}
		
		if (curState.hasOutgoing())
			replaceOrRegister(curState);
	
		addSuffix(curState, seq.substring(i));
	}

	/**
	 * Add all sequences from a lexicographically sorted collection.
	 * @param seqs
	 * @throws DictionaryBuilderException
	 */
	public void addAll(Collection<String> seqs) throws DictionaryBuilderException
	{
		for (String seq: seqs)
			add(seq);
	}

	/**
	 * Create a dictionary automaton. This also finalizes the {@link DictionaryBuilder}.
	 * @return
	 */
	public Dictionary build()
	{
		return build(false);
	}
	
	/**
	 * Create a perfect hash automaton. This also finalizes the {@link DictionaryBuilder}.
	 * @return
	 */
	public PerfectHashDictionary buildPerfectHash()
	{
		return (PerfectHashDictionary) build(true);
	}
	
	private void finalizeDictionary()
	{
		if (!d_finalized)
		{
			replaceOrRegister(d_startState);
			d_finalized = true;
		}
	}
	
	/**
	 * Obtain a Graphviz dot representation of the automaton. This finalizes the
	 * {@link DictionaryBuilder}.
	 * @return
	 */
	public String toDot()
	{
		finalizeDictionary();
		
		StringBuilder stringBuilder = new StringBuilder();
		stringBuilder.append("digraph G {\n");
		
		Map<State, Integer> states = numberedStates();
		for (Entry<State, Integer> numberedState: states.entrySet())
		{
			State s = numberedState.getKey();
			Integer sn = numberedState.getValue();
			
			if (s.isFinal())
				stringBuilder.append(String.format("%d [peripheries=2];\n", numberedState.getValue()));
			
			for (Entry<Character, State> trans: s.transitions().entrySet())
				stringBuilder.append(String.format("%d -> %d [label=\"%c\"];\n", sn, states.get(trans.getValue()), trans.getKey()));
		}
		
		stringBuilder.append("}");
		
		return stringBuilder.toString();
	}

	private void addSuffix(State s, String suffix)
	{
		for (int i = 0; i < suffix.length(); i++)
		{
			State newState = new State();
			s.addTransition(suffix.charAt(i), newState);
			s = newState;
		}
		
		// s is now a final state.
		s.setFinal(true);
	}

	private Dictionary build(boolean perfectHash)
	{
		finalizeDictionary();
			
		Map<State, Integer> stateNumbers = numberedStates();
		State[] sList = stateList(stateNumbers);
		
		// First compute the offsets of each state in the state table.
		int[] offsets = new int[stateNumbers.size()];
		for (int i = 1; i < stateNumbers.size(); i++)
			offsets[i] = offsets[i - 1] + sList[i - 1].transitions().size();
	
		// Create transition tables.
		int nTransitions = offsets[offsets.length - 1] + sList[offsets.length - 1].transitions().size();
		char[] transChars = new char[nTransitions];
		int[] transTo = new int[nTransitions];
		
		// Final state set.
		Set<Integer> finalStates = new HashSet<Integer>();
		
		// Construct the transition table.
		for (int i = 0; i < sList.length; i++)
		{
			int j = 0;
			for (Entry<Character, State> trans: sList[i].transitions().entrySet())
			{
				transChars[offsets[i] + j] = trans.getKey();
				transTo[offsets[i] + j] = stateNumbers.get(trans.getValue());
				++j;
			}
			
			if (sList[i].isFinal())
				finalStates.add(i);
		}
	
		if (perfectHash)
			return new PerfectHashDictionary(offsets, transChars, transTo, finalStates);
		else
			return new Dictionary(offsets, transChars, transTo, finalStates);
	}

	private Map<State, Integer> numberedStates()
	{
		Map<State, Integer> states = new HashMap<State, Integer>();
		
		Queue<State> stateQueue = new LinkedList<State>();
		stateQueue.add(d_startState);
		while (!stateQueue.isEmpty())
		{
			State s = stateQueue.poll();
			
			if (states.containsKey(s))
				continue;
			
			states.put(s, states.size());
			
			for (State to: s.transitions().values())
				stateQueue.add(to);
		}
		
		return states;
	}
	
	private void replaceOrRegister(State s)
	{
		State child = s.lastState();
		
		if (child.hasOutgoing())
		{
			replaceOrRegister(child);
		}
		
		State replacement = d_register.get(child);
		if (replacement != null)
			s.setLastState(replacement);
		else
			d_register.put(child, child);
	}

	private State[] stateList(Map<State, Integer> numberedStates)
	{
		State[] r = new State[numberedStates.size()];
		
		for (Entry<State, Integer> numberedState: numberedStates.entrySet())
			r[numberedState.getValue()] = numberedState.getKey();
		
		return r;
	}
}
