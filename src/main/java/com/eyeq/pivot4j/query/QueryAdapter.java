/*
 * ====================================================================
 * This software is subject to the terms of the Common Public License
 * Agreement, available at the following URL:
 *   http://www.opensource.org/licenses/cpl.html .
 * You must accept the terms of that agreement to use this software.
 * ====================================================================
 */
package com.eyeq.pivot4j.query;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.NullArgumentException;
import org.apache.commons.lang.ObjectUtils;
import org.olap4j.Axis;
import org.olap4j.CellSet;
import org.olap4j.CellSetAxis;
import org.olap4j.OlapException;
import org.olap4j.Position;
import org.olap4j.metadata.Dimension;
import org.olap4j.metadata.Hierarchy;
import org.olap4j.metadata.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.eyeq.pivot4j.PivotException;
import com.eyeq.pivot4j.PivotModel;
import com.eyeq.pivot4j.el.EvaluationFailedException;
import com.eyeq.pivot4j.el.ExpressionEvaluator;
import com.eyeq.pivot4j.el.ExpressionEvaluatorFactory;
import com.eyeq.pivot4j.mdx.AbstractExpVisitor;
import com.eyeq.pivot4j.mdx.CompoundId;
import com.eyeq.pivot4j.mdx.Exp;
import com.eyeq.pivot4j.mdx.ExpressionParameter;
import com.eyeq.pivot4j.mdx.FunCall;
import com.eyeq.pivot4j.mdx.Literal;
import com.eyeq.pivot4j.mdx.MdxParser;
import com.eyeq.pivot4j.mdx.MdxStatement;
import com.eyeq.pivot4j.mdx.MemberParameter;
import com.eyeq.pivot4j.mdx.QueryAxis;
import com.eyeq.pivot4j.mdx.Syntax;
import com.eyeq.pivot4j.mdx.ValueParameter;
import com.eyeq.pivot4j.mdx.impl.MdxParserImpl;
import com.eyeq.pivot4j.mdx.metadata.MemberExp;
import com.eyeq.pivot4j.state.Bookmarkable;

/**
 * Adapt the MDX query to the model
 */
public class QueryAdapter implements Bookmarkable {

	protected Logger logger = LoggerFactory.getLogger(getClass());

	private PivotModel model;

	private List<Quax> quaxes; // Array of query axis state object

	private boolean useQuax = false;

	private boolean axesSwapped = false;

	private Quax quaxToSort; // this is the Quax to be sorted

	private MdxStatement parsedQuery;

	private MdxStatement cloneQuery;

	private Collection<QueryChangeListener> listeners = new ArrayList<QueryChangeListener>();

	private QuaxChangeListener quaxListener = new QuaxChangeListener() {

		public void quaxChanged(QuaxChangeEvent e) {
			onQuaxChanged(e.getQuax(), e.isChangedByNavigator());
		}
	};

	/**
	 * @param model
	 * @param parser
	 */
	public QueryAdapter(PivotModel model) {
		if (model == null) {
			throw new NullArgumentException("model");
		}

		this.model = model;
	}

	public void initialize() {
		this.useQuax = false;
		this.axesSwapped = false;
		this.quaxToSort = null;

		this.parsedQuery = parseQuery(model.getMdx());
		this.cloneQuery = null;

		List<QueryAxis> queryAxes = parsedQuery.getAxes();

		this.quaxes = new ArrayList<Quax>(queryAxes.size());

		int ordinal = 0;
		for (@SuppressWarnings("unused")
		QueryAxis queryAxis : queryAxes) {
			Quax quax = new Quax(ordinal++, model.getCube());
			quax.addChangeListener(quaxListener);

			quaxes.add(quax);
		}
	}

	public boolean isInitialized() {
		if (quaxes == null || quaxes.isEmpty()) {
			return false;
		}

		for (Quax quax : quaxes) {
			if (!quax.isInitialized()) {
				return false;
			}
		}

		return true;
	}

	/**
	 * @return the model
	 */
	public PivotModel getModel() {
		return model;
	}

	public String getCubeName() {
		CompoundId cube = parsedQuery.getCube();

		if (cube != null && !cube.getNames().isEmpty()) {
			return cube.getNames().get(0).getUnquotedName();
		}

		return null;
	}

	/**
	 * Register change listener
	 * 
	 * @param listener
	 */
	public void addChangeListener(QueryChangeListener listener) {
		listeners.add(listener);
	}

	/**
	 * Unregister change listener
	 * 
	 * @param listener
	 */
	public void removeChangeListener(QueryChangeListener listener) {
		listeners.remove(listener);
	}

	protected void fireQueryChanged() {
		fireQueryChanged(true);
	}

	protected void fireQueryChanged(boolean update) {
		if (update) {
			this.useQuax = true;

			updateQuery();
		}

		QueryChangeEvent e = new QueryChangeEvent(this);

		List<QueryChangeListener> copiedListeners = new ArrayList<QueryChangeListener>(
				listeners);
		for (QueryChangeListener listener : copiedListeners) {
			listener.queryChanged(e);
		}
	}

	/**
	 * @return the XMLA Query object
	 */
	protected MdxStatement getParsedQuery() {
		return parsedQuery;
	}

	/**
	 * @param evaluated
	 * @return
	 */
	public String getCurrentMdx(final boolean evaluated) {
		MdxStatement stmt = parsedQuery.clone();

		stmt.accept(new AbstractExpVisitor() {

			@Override
			public void visitMemberParameter(MemberParameter exp) {
				exp.setEvaluated(evaluated);
			}

			@Override
			public void visitValueParameter(ValueParameter exp) {
				exp.setEvaluated(evaluated);
			}
		});

		return stmt.toMdx();
	}

	/**
	 * @param factory
	 */
	public void evaluate(final ExpressionEvaluatorFactory factory) {
		parsedQuery.accept(new AbstractExpVisitor() {

			@Override
			public void visitMemberParameter(MemberParameter exp) {
				ExpressionEvaluator evaluator = factory.getEvaluator(exp
						.getNamespace());
				evaluate(evaluator, exp);
			}

			@Override
			public void visitValueParameter(ValueParameter exp) {
				ExpressionEvaluator evaluator = factory.getEvaluator(exp
						.getNamespace());
				evaluate(evaluator, exp);
			}
		});
	}

	/**
	 * @param evaluator
	 * @param exp
	 */
	protected void evaluate(ExpressionEvaluator evaluator,
			ExpressionParameter exp) {
		if (evaluator == null) {
			throw new EvaluationFailedException(
					"No expression evaluator found for namespace : "
							+ exp.getNamespace(), exp.getNamespace(),
					exp.getExpression());
		}

		Map<String, Object> context = model.getExpressionContext();

		Object result = evaluator.evaluate(exp.getExpression(), context);
		exp.setResult(ObjectUtils.toString(result));
	}

	/**
	 * @return
	 */
	public List<Quax> getQuaxes() {
		if (!isInitialized()) {
			getModel().getCellSet();
		}

		return Collections.unmodifiableList(quaxes);
	}

	/**
	 * @return true if quas is to be used
	 */
	public boolean getUseQuax() {
		return useQuax;
	}

	/**
	 * @return true, if axes are currently swapped
	 */
	public boolean isAxesSwapped() {
		return axesSwapped;
	}

	/**
	 * @param axesSwapped
	 */
	public void setAxesSwapped(boolean axesSwapped) {
		if (axesSwapped != this.axesSwapped) {
			QueryAxis columnAxis = parsedQuery.getAxis(Axis.COLUMNS);
			QueryAxis rowAxis = parsedQuery.getAxis(Axis.ROWS);

			if (columnAxis != null && rowAxis != null) {
				this.axesSwapped = axesSwapped;

				Exp exp = columnAxis.getExp();
				columnAxis.setExp(rowAxis.getExp());
				rowAxis.setExp(exp);

				int columnAxisOrdinal = parsedQuery.getAxes().indexOf(
						columnAxis);
				int rowAxisOrdinal = parsedQuery.getAxes().indexOf(rowAxis);

				Quax quax = quaxes.get(columnAxisOrdinal);
				quaxes.set(columnAxisOrdinal, quaxes.get(rowAxisOrdinal));
				quaxes.set(rowAxisOrdinal, quax);

				fireQueryChanged();
			}
		}
	}

	public boolean isNonEmpty() {
		boolean nonEmpty = true;

		List<QueryAxis> queryAxes = parsedQuery.getAxes();
		for (QueryAxis axis : queryAxes) {
			nonEmpty &= axis.isNonEmpty();
		}

		return !queryAxes.isEmpty() && nonEmpty;
	}

	/**
	 * @param nonEmpty
	 */
	public void setNonEmpty(boolean nonEmpty) {
		boolean changed = nonEmpty != isNonEmpty();

		if (changed) {
			List<QueryAxis> queryAxes = parsedQuery.getAxes();
			for (QueryAxis axis : queryAxes) {
				axis.setNonEmpty(nonEmpty);
			}

			fireQueryChanged(false);
		}
	}

	/**
	 * @return the quaxToSort
	 */
	public Quax getQuaxToSort() {
		return quaxToSort;
	}

	/**
	 * @param quaxToSort
	 *            the quaxToSort to set
	 */
	public void setQuaxToSort(Quax quaxToSort) {
		this.quaxToSort = quaxToSort;
		updateQuery();
	}

	protected boolean isSortOnQuery() {
		return model.isSorting() && model.getSortPosMembers() != null
				&& !model.getSortPosMembers().isEmpty();
	}

	/**
	 * @return ordinal of quax to sort, if sorting is active
	 */
	protected int activeQuaxToSort() {
		if (isSortOnQuery()) {
			return quaxToSort.getOrdinal();
		} else {
			return -1;
		}
	}

	/**
	 * find the Quax for a specific dimension
	 * 
	 * @param dim
	 *            Dimension
	 * @return Quax containg dimension
	 */
	public Quax findQuax(Dimension dim) {
		if (!isInitialized()) {
			getModel().getCellSet();
		}

		for (Quax quax : quaxes) {
			if (quax.dimIdx(dim) >= 0) {
				return quax;
			}
		}
		return null;
	}

	/**
	 * Update the Query Object before Execute. The current query is build from -
	 * the original query - adding the drilldown groups - apply pending swap
	 * axes - apply pending sorts.
	 */
	public MdxStatement updateQuery() {
		// if quax is to be used, generate axes from quax
		if (useQuax) {
			int iQuaxToSort = activeQuaxToSort();

			List<QueryAxis> qAxes = parsedQuery.getAxes();

			int i = 0;
			for (Quax quax : quaxes) {
				if (quax.getPosTreeRoot() == null) {
					continue;
				}

				boolean doHierarchize = false;
				if (quax.isHierarchizeNeeded() && i != iQuaxToSort) {
					doHierarchize = true;

					if (logger.isDebugEnabled()) {
						logger.debug("MDX Generation added Hierarchize()");
					}
				}

				Exp eSet = quax.genExp(doHierarchize);
				qAxes.get(i).setExp(eSet);

				i++;
			}
		}

		// generate order function if neccessary
		if (!useQuax) {
			// if Quax is used, the axis exp's are re-generated every time.
			// if not -
			// adding a sort to the query must not be permanent.
			// Therefore, we clone the orig state of the query object and
			// use
			// the clone furthermore in order to avoid duplicate "Order"
			// functions.
			if (cloneQuery == null) {
				if (isSortOnQuery()) {
					this.cloneQuery = parsedQuery.clone();
				}
			} else {
				// reset to original state
				if (isSortOnQuery()) {
					this.parsedQuery = cloneQuery.clone();
				} else {
					this.parsedQuery = cloneQuery;
				}
			}
		}

		addSortToQuery();

		return parsedQuery;
	}

	/**
	 * Apply sort to query
	 */
	public void addSortToQuery() {
		if (isSortOnQuery()) {
			switch (model.getSortCriteria()) {
			case ASC:
			case DESC:
			case BASC:
			case BDESC:
				// call sort
				orderAxis(parsedQuery);
				break;
			case TOPCOUNT:
				topBottomAxis(parsedQuery, "TopCount");
				break;
			case BOTTOMCOUNT:
				topBottomAxis(parsedQuery, "BottomCount");
				break;
			default:
				return; // do nothing
			}
		}
	}

	/**
	 * Add Order Funcall to QueryAxis
	 * 
	 * @param monAx
	 * @param monSortMode
	 */
	protected void orderAxis(MdxStatement pq) {
		// Order(TopCount) is allowed, Order(Order) is not permitted
		List<QueryAxis> queryAxes = pq.getAxes();
		QueryAxis qa = queryAxes.get(quaxToSort.getOrdinal());

		Exp setForAx = qa.getExp();

		// setForAx is the top level Exp of the axis
		// put an Order FunCall around
		List<Exp> args = new ArrayList<Exp>(3);
		args.add(setForAx); // the set to be sorted is the set representing the
							// query axis
		// if we got more than 1 position member, generate a tuple for the 2.arg
		Exp sortExp;

		List<Member> sortPosMembers = model.getSortPosMembers();
		if (sortPosMembers == null) {
			return;
		}

		if (sortPosMembers.size() > 1) {
			List<Exp> memberExp = new ArrayList<Exp>(sortPosMembers.size());

			for (Member member : sortPosMembers) {
				memberExp.add(new MemberExp(member));
			}

			sortExp = new FunCall("()", Syntax.Parentheses, memberExp);
		} else {
			sortExp = new MemberExp(sortPosMembers.get(0));
		}

		args.add(sortExp);
		args.add(Literal.createString(model.getSortCriteria().name()));

		FunCall order = new FunCall("Order", Syntax.Function, args);
		qa.setExp(order);
	}

	/**
	 * Add Top/BottomCount Funcall to QueryAxis
	 * 
	 * @param monAx
	 * @param nShow
	 */
	protected void topBottomAxis(MdxStatement pq, String function) {
		// TopCount(TopCount) and TopCount(Order) is not permitted
		List<QueryAxis> queryAxes = pq.getAxes();

		QueryAxis qa = queryAxes.get(quaxToSort.getOrdinal());
		Exp setForAx = qa.getExp();
		Exp sortExp;

		List<Member> sortPosMembers = model.getSortPosMembers();
		if (sortPosMembers == null) {
			return;
		}

		// if we got more than 1 position member, generate a tuple
		if (sortPosMembers.size() > 1) {
			List<Exp> memberExp = new ArrayList<Exp>(sortPosMembers.size());

			for (Member member : sortPosMembers) {
				memberExp.add(new MemberExp(member));
			}

			sortExp = new FunCall("()", Syntax.Parentheses, memberExp);
		} else {
			sortExp = new MemberExp(sortPosMembers.get(0));
		}

		List<Exp> args = new ArrayList<Exp>(3);

		args.add(setForAx); // the set representing the query axis
		args.add(Literal.create(model.getTopBottomCount()));
		args.add(sortExp);

		FunCall topbottom = new FunCall(function, Syntax.Function, args);
		qa.setExp(topbottom);
	}

	/**
	 * @param parsedQuery
	 */
	protected MdxStatement parseQuery(String mdxQuery) {
		MdxParser parser = new MdxParserImpl();

		return parser.parse(mdxQuery);
	}

	/**
	 * After the startup query was run: get the current positions as array of
	 * array of member. Called from Model.getResult after the query was
	 * executed.
	 * 
	 * @param result
	 *            the result which redefines the query axes
	 */
	public void afterExecute(CellSet cellSet) {
		List<CellSetAxis> axes = cellSet.getAxes();

		// initialization: get the result positions and set it to quax
		// if the quaxes are not yet used to generate the query
		if (!useQuax) {
			int i = 0;
			for (CellSetAxis axis : axes) {
				List<Position> positions = axis.getPositions();

				int index = axesSwapped ? (i + 1) % 2 : i;
				quaxes.get(index).initialize(positions);

				i++;
			}
		} else {
			// hierarchize result if neccessary
			int i = 0;
			for (Quax quax : quaxes) {
				int index = axesSwapped ? (i + 1) % 2 : i;
				List<Position> positions = axes.get(index).getPositions();

				// after a result for CalcSet.GENERATE was gotten
				// we have to re-initialize the quax,
				// so that we can navigate.
				if (quax.getGenerateMode() == CalcSetMode.Generate) {
					quax.resetGenerate();
					quax.initialize(positions);
				} else {
					// unknown function members are collected
					// - always for a "Sticky generate" unknown function
					// - on first result for any other unknown function
					int nDimension = quax.getNDimension();
					for (int j = 0; j < nDimension; j++) {
						// collect members for unknown functions on quax
						if (quax.isUnknownFunction(j)) {
							List<Member> members = memListForHier(j, positions);
							quax.setHierMemberList(j, members);
						}
					}
				}
				i++;
			}
		}

		if (logger.isDebugEnabled()) {
			// print the result positions to logger
			for (CellSetAxis axis : axes) {
				List<Position> positions = axis.getPositions();
				logger.debug("Positions of axis "
						+ axis.getAxisOrdinal().axisOrdinal());

				if (positions.size() == 0) {
					// the axis does not have any positions
					logger.debug("0 positions");
				} else {
					int nDimension = positions.get(0).getMembers().size();
					for (Position position : positions) {
						List<Member> members = position.getMembers();

						StringBuilder sb = new StringBuilder();
						for (int j = 0; j < nDimension; j++) {
							if (j > 0) {
								sb.append(" * ");
							}

							List<Member> memsj = new ArrayList<Member>(j + 1);
							for (int k = 0; k <= j; k++) {
								memsj.add(members.get(k));
							}

							if (this.canExpand(memsj)) {
								sb.append("(+)");
							} else if (this.canCollapse(memsj)) {
								sb.append("(-)");
							} else {
								sb.append("   ");
							}

							sb.append(members.get(j).getUniqueName());
						}
						logger.debug(sb.toString());
					}
				}
			}
		}
	}

	/**
	 * Extract members of hier from Result
	 * 
	 * @param hierIndex
	 * @return members of hier
	 */
	protected List<Member> memListForHier(int hierIndex,
			List<Position> positions) {
		List<Member> members = new ArrayList<Member>();
		for (Position position : positions) {
			Member member = position.getMembers().get(hierIndex);
			if (!members.contains(member)) {
				members.add(member);
			}
		}

		return members;
	}

	/**
	 * Create set expression for list of members
	 * 
	 * @param members
	 * @return set expression
	 */
	protected Object createMemberSet(List<Member> members) {
		List<Exp> exps = new ArrayList<Exp>(members.size());

		for (Member member : members) {
			exps.add(new MemberExp(member));
		}

		return new FunCall("{}", Syntax.Braces, exps);
	}

	/**
	 * Find out, whether a member can be expanded. this is true, if - the member
	 * is on an axis and - the member is not yet expanded and - the member has
	 * children
	 * 
	 * @param member
	 *            Member to be expanded
	 * @return true if the member can be expanded
	 */
	public boolean canExpand(Member member) {
		// a calculated member cannot be expanded
		if (member.isCalculated()) {
			return false;
		}

		try {
			if (member.getChildMemberCount() <= 0) {
				return false;
			}
		} catch (OlapException e) {
			throw new PivotException(e);
		}

		Dimension dim = member.getLevel().getHierarchy().getDimension();
		Quax quax = findQuax(dim);

		return (quax == null) ? false : quax.canExpand(member);
	}

	/**
	 * @param pathMembers
	 *            Members to be expanded
	 * @return true if the member can be expanded
	 */
	public boolean canExpand(List<Member> pathMembers) {
		if (pathMembers.isEmpty()) {
			return false;
		}

		Member member = pathMembers.get(pathMembers.size() - 1);
		// a calculated member cannot be expanded
		if (member.isCalculated()) {
			return false;
		}

		try {
			if (member.getChildMemberCount() <= 0) {
				return false;
			}
		} catch (OlapException e) {
			throw new PivotException(e);
		}

		Dimension dim = member.getLevel().getHierarchy().getDimension();
		Quax quax = findQuax(dim);

		return (quax == null) ? false : quax.canExpand(pathMembers);
	}

	/**
	 * @param member
	 *            Member to be collapsed
	 * @return true if the member can be collapsed
	 */
	public boolean canCollapse(Member member) {
		// a calculated member cannot be collapsed
		if (member.isCalculated()) {
			return false;
		}

		Dimension dim = member.getLevel().getHierarchy().getDimension();
		Quax quax = findQuax(dim);

		return (quax == null) ? false : quax.canCollapse(member);
	}

	/**
	 * @param position
	 *            position to be expanded
	 * @return true if the position can be collapsed
	 */
	public boolean canCollapse(List<Member> pathMembers) {
		if (pathMembers.isEmpty()) {
			return false;
		}

		Member member = pathMembers.get(pathMembers.size() - 1);
		// a calculated member cannot be expanded
		if (member.isCalculated()) {
			return false;
		}

		Dimension dim = member.getLevel().getHierarchy().getDimension();
		Quax quax = findQuax(dim);

		return (quax == null) ? false : quax.canCollapse(pathMembers);
	}

	/**
	 * Expand a member in all positions this is done by applying
	 * ToggleDrillState to the Query
	 * 
	 * @param member
	 *            member to be expanded
	 */
	public void expand(Member member) {
		Dimension dim = member.getLevel().getHierarchy().getDimension();
		Quax quax = findQuax(dim);

		if (logger.isInfoEnabled()) {
			logger.info("Expand member" + getPositionString(null, member));
		}

		if ((quax == null) || !quax.canExpand(member)) {
			String msg = "Expand member failed for" + member.getUniqueName();
			throw new PivotException(msg);
		}

		quax.expand(member);
	}

	/**
	 * Expand a member in a specific position
	 * 
	 * @param pathMembers
	 *            members to be expanded
	 */
	public void expand(List<Member> pathMembers) {
		Member member = pathMembers.get(pathMembers.size() - 1);
		Dimension dim = member.getLevel().getHierarchy().getDimension();
		Quax quax = findQuax(dim);

		if (logger.isInfoEnabled()) {
			logger.info("Expand path" + getPositionString(pathMembers, null));
		}

		if ((quax == null) || !quax.canExpand(pathMembers)) {
			String msg = "Expand failed for"
					+ getPositionString(pathMembers, null);
			throw new PivotException(msg);
		}

		quax.expand(pathMembers);
	}

	/**
	 * Collapse a member in all positions
	 * 
	 * @param member
	 *            Member to be collapsed
	 */
	public void collapse(Member member) {
		Dimension dim = member.getLevel().getHierarchy().getDimension();

		if (logger.isInfoEnabled()) {
			logger.info("Collapse " + member.getUniqueName());
		}

		Quax quax = findQuax(dim);
		if (quax == null) {
			String msg = "Collapse quax was null " + member.getUniqueName();
			throw new PivotException(msg);
		}

		quax.collapse(member);
	}

	/**
	 * Collapse a member in a specific position
	 * 
	 * @param position
	 *            Position to be collapsed
	 */
	public void collapse(List<Member> pathMembers) {
		if (logger.isDebugEnabled()) {
			logger.debug("Collapse" + getPositionString(pathMembers, null));
		}

		Member member = pathMembers.get(pathMembers.size() - 1);
		Dimension dim = member.getLevel().getHierarchy().getDimension();
		Quax quax = findQuax(dim);
		if (quax == null) {
			String msg = "Collapse quax was null"
					+ getPositionString(pathMembers, null);
			throw new PivotException(msg);
		}

		quax.collapse(pathMembers);
	}

	/**
	 * Drill down is possible if <code>member</code> has children
	 * 
	 * @param member
	 *            Member to drill down
	 */
	public boolean canDrillDown(Member member) {
		try {
			if (member.getChildMemberCount() <= 0) {
				return false;
			}
		} catch (OlapException e) {
			throw new PivotException(e);
		}

		Dimension dim = member.getLevel().getHierarchy().getDimension();
		Quax quax = findQuax(dim);
		return (quax == null) ? false : quax.canDrillDown(member);
	}

	/**
	 * Drill up is possible if at least one member in the tree is not at the top
	 * level of this hierarchy.
	 */
	public boolean canDrillUp(Hierarchy hierarchy) {
		Quax quax = findQuax(hierarchy.getDimension());
		return (quax == null) ? false : quax.canDrillUp(hierarchy);
	}

	/**
	 * After switch to Qubon mode: replaces the members. Let <code>H</code> be
	 * the hierarchy that member belongs to. Then drillDown will replace all
	 * members from <code>H</code> that are currently visible with the children
	 * of <code>member</code>.
	 */
	public void drillDown(Member member) {
		// switch to Qubon mode, if not yet in
		Quax quax = findQuax(member.getLevel().getHierarchy().getDimension());

		if (quax == null) {
			logger.info("drillDown Quax was null"
					+ getPositionString(null, member));
			return;
		}

		// replace dimension iDim by monMember.children
		quax.drillDown(member);

		if (logger.isInfoEnabled()) {
			logger.info("Drill down " + getPositionString(null, member));
		}
	}

	/**
	 * After switch to Qubon mode: replaces all visible members of hier with the
	 * members of the next higher level.
	 */
	public void drillUp(Hierarchy hierarchy) {
		// switch to Qubon mode, if not yet in
		Quax quax = findQuax(hierarchy.getDimension());
		if (quax == null) {
			String msg = "Drill up hierarchy quax was null "
					+ hierarchy.getCaption();
			throw new PivotException(msg);
		}

		quax.drillUp(hierarchy);

		if (logger.isInfoEnabled())
			logger.info("Drill up hierarchy " + hierarchy.getCaption());
	}

	/**
	 * @param slicerExp
	 */
	public void changeSlicer(Exp exp) {
		parsedQuery.setSlicer(exp);
		fireQueryChanged(false);
	}

	/**
	 * Display position member for debugging purposes
	 * 
	 * @param posMembers
	 * @param member
	 * @return
	 */
	protected String getPositionString(List<Member> posMembers, Member member) {
		StringBuilder sb = new StringBuilder();
		if (posMembers != null) {
			sb.append(" Position=");
			int i = 0;
			for (Member m : posMembers) {
				if (i > 0) {
					sb.append(" ");
				}
				sb.append(m.getUniqueName());
				i++;
			}
		}

		if (member != null) {
			sb.append(" Member=");
			sb.append(member.getUniqueName());
		}

		return sb.toString();
	}

	/**
	 * @param quax
	 * @param changedByNavigator
	 */
	protected void onQuaxChanged(Quax quax, boolean changedByNavigator) {
		// if the axis to sort (normaly *not* the measures)
		// was changed by the Navi GUI, we want to switch sorting off
		if (changedByNavigator && model.isSorting() && quax == getQuaxToSort()) {
			if (logger.isDebugEnabled()) {
				logger.debug("Quax changed by navi - switch sorting off");
			}

			model.setSorting(false);
		}

		fireQueryChanged();
	}

	/**
	 * @see com.eyeq.pivot4j.state.Bookmarkable#saveState()
	 */
	public Serializable saveState() {
		Serializable[] state = new Serializable[4];

		state[0] = isAxesSwapped();
		state[1] = getUseQuax();

		Quax quaxToSort = getQuaxToSort();

		if (quaxToSort == null) {
			state[2] = -1;
		} else {
			state[2] = quaxToSort.getOrdinal();
		}

		if (getUseQuax()) {
			List<Quax> quaxes = getQuaxes();

			Serializable[] quaxStates = new Serializable[quaxes.size()];
			for (int i = 0; i < quaxStates.length; i++) {
				quaxStates[i] = quaxes.get(i).saveState();
			}

			state[3] = quaxStates;
		} else {
			state[3] = null;
		}

		return state;
	}

	/**
	 * @see com.eyeq.pivot4j.state.Bookmarkable#restoreState(java.io.Serializable)
	 */
	public void restoreState(Serializable state) {
		Serializable[] states = (Serializable[]) state;

		this.axesSwapped = (Boolean) states[0];
		this.useQuax = (Boolean) states[1];

		int quaxOrdinal = (Integer) states[2];

		Quax quaxToSort = null;

		if (quaxOrdinal > -1) {
			List<Quax> quaxes = getQuaxes();
			for (Quax quax : quaxes) {
				if (quaxOrdinal == quax.getOrdinal()) {
					quaxToSort = quax;
					break;
				}
			}
		}

		this.quaxToSort = quaxToSort;

		if (useQuax) {
			Serializable[] quaxStates = (Serializable[]) states[3];

			// reset the quaxes to current state
			List<Quax> quaxes = getQuaxes();
			if (quaxes.size() != quaxStates.length) {
				throw new IllegalArgumentException(
						"Stored quax state is not compatible with the current MDX.");
			}

			for (int i = 0; i < quaxStates.length; i++) {
				quaxes.get(i).restoreState(quaxStates[i]);
			}
		}
	}
}
