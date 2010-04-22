/**
 * Copyright (c) 2000-2010 Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.portal.workflow.jbpm.dao;

import com.liferay.portal.kernel.dao.orm.QueryUtil;
import com.liferay.portal.kernel.util.OrderByComparator;
import com.liferay.portal.kernel.workflow.ContextConstants;
import com.liferay.portal.kernel.workflow.WorkflowLog;
import com.liferay.portal.workflow.jbpm.WorkflowDefinitionExtensionImpl;
import com.liferay.portal.workflow.jbpm.WorkflowLogImpl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.Criteria;
import org.hibernate.Session;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.Disjunction;
import org.hibernate.criterion.Junction;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.ProjectionList;
import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Restrictions;

import org.jbpm.JbpmContext;
import org.jbpm.JbpmException;
import org.jbpm.graph.def.ProcessDefinition;
import org.jbpm.graph.exe.ProcessInstance;
import org.jbpm.taskmgmt.exe.TaskInstance;

/**
 * <a href="CustomSession.java.html"><b><i>View Source</i></b></a>
 *
 * @author Shuyang Zhou
 * @author Brian Wing Shun Chan
 * @author Marcellus Tavares
 */
public class CustomSession {

	public static String COUNT_LATEST_PROCESS_DEFINITIONS =
		CustomSession.class.getName() + ".countLatestProcessDefinitions";

	public static String COUNT_PROCESS_DEFINITIONS_BY_NAME =
		CustomSession.class.getName() + ".countProcessDefinitionsByName";

	public CustomSession(JbpmContext jbpmContext) {
		_session = jbpmContext.getSession();
	}

	public void close() {
		if (_session != null) {
			_session.close();
		}
	}

	public int countProcessDefinitions(String name, boolean latest) {
		try {
			Criteria criteria = _session.createCriteria(
				ProcessDefinition.class);

			if (latest) {
				criteria.setProjection(Projections.countDistinct("name"));
			}
			else {
				criteria.setProjection(Projections.rowCount());
			}

			if (name != null) {
				criteria.add(Restrictions.eq("name", name));
			}

			Number count = (Number)criteria.uniqueResult();

			return count.intValue();
		}
		catch (Exception e) {
			throw new JbpmException(e);
		}
	}

	public int countProcessInstances(
		long processDefinitionId, Boolean completed) {

		try {
			Criteria criteria = _session.createCriteria(
				ProcessInstance.class);

			criteria.setProjection(Projections.countDistinct("id"));

			criteria.add(
				Restrictions.eq("processDefinition.id", processDefinitionId));

			if (completed != null) {
				if (completed.booleanValue()) {
					criteria.add(Restrictions.isNotNull("end"));
				}
				else {
					criteria.add(Restrictions.isNull("end"));
				}
			}

			Number count = (Number)criteria.uniqueResult();

			return count.intValue();
		}
		catch (Exception e) {
			throw new JbpmException(e);
		}
	}

	public int countTaskInstances(
		long processInstanceId, long tokenId, String[] actorIds,
		boolean pooledActors, Boolean completed) {

		if ((actorIds != null) && (actorIds.length == 0)) {
			return 0;
		}

		try {
			Criteria criteria = _session.createCriteria(TaskInstance.class);

			criteria.setProjection(Projections.countDistinct("id"));

			if (processInstanceId > 0) {
				criteria.add(
					Restrictions.eq("processInstance.id", processInstanceId));
			}
			else if (tokenId > 0) {
				criteria.add(Restrictions.eq("token.id", tokenId));
			}
			else if (actorIds != null) {
				if (pooledActors) {
					Criteria subcriteria = criteria.createCriteria(
						"pooledActors");

					subcriteria.add(Restrictions.in("actorId", actorIds));
				}
				else {
					criteria.add(Restrictions.in("actorId", actorIds));
				}
			}

			if (completed != null) {
				if (completed.booleanValue()) {
					criteria.add(Restrictions.isNotNull("end"));
				}
				else {
					criteria.add(Restrictions.isNull("end"));
				}
			}

			Number count = (Number)criteria.uniqueResult();

			return count.intValue();
		}
		catch (Exception e) {
			throw new JbpmException(e);
		}
	}

	public void deleteWorkflowDefinitionExtension(long processDefinitionId) {
		WorkflowDefinitionExtensionImpl workflowDefinitionExtension =
			findWorkflowDefinitonExtension(processDefinitionId);

		_session.delete(workflowDefinitionExtension);
	}

	public void deleteWorkflowLogs(long processInstanceId) {
		List<TaskInstance> taskInstances = findTaskInstances(
			processInstanceId, 1, null, false, null, QueryUtil.ALL_POS,
			QueryUtil.ALL_POS, null);

		for (TaskInstance taskInstance : taskInstances) {
			Criteria criteria = _session.createCriteria(WorkflowLogImpl.class);

			criteria.add(
				Restrictions.eq("taskInstance.id", taskInstance.getId()));

			List<WorkflowLog> workflowLogs = criteria.list();

			for (WorkflowLog workflowLog : workflowLogs) {
				_session.delete(workflowLog);
			}
		}
	}

	public List<ProcessDefinition> findProcessDefinitions(
		String name, boolean latest, int start, int end,
		OrderByComparator orderByComparator) {

		try {
			Criteria criteria = _session.createCriteria(
				ProcessDefinition.class);

			if (latest) {
				ProjectionList projectionList = Projections.projectionList();

				projectionList.add(Projections.groupProperty("name"));
				projectionList.add(Projections.max("version"));

				criteria.setProjection(projectionList);

				addOrder(criteria, orderByComparator, "version");
			}

			if (name != null) {
				criteria.add(Restrictions.eq("name", name));

				addOrder(criteria, orderByComparator, "name");
			}

			if (latest == false && name == null) {
				addOrder(criteria, orderByComparator);
			}

			addPagination(criteria, start, end);

			if (latest) {
				List<Object[]> list = criteria.list();

				List<String> names = new ArrayList<String>(list.size());

				for (Object[] array : list) {
					names.add((String)array[0]);
				}

				return findProcessDefinitions(names);
			}
			else {
				return criteria.list();
			}
		}
		catch (Exception e) {
			throw new JbpmException(e);
		}
	}

	public List<ProcessInstance> findProcessInstances(
		long processDefinitionId, Boolean completed, int start, int end,
		OrderByComparator orderByComparator) {

		try {
			Criteria criteria = _session.createCriteria(
				ProcessInstance.class);

			criteria.add(
				Restrictions.eq("processDefinition.id", processDefinitionId));

			if (completed != null) {
				if (completed.booleanValue()) {
					criteria.add(Restrictions.isNotNull("end"));
				}
				else {
					criteria.add(Restrictions.isNull("end"));
				}
			}

			addPagination(criteria, start, end);
			addOrder(criteria, orderByComparator);

			return criteria.list();
		}
		catch (Exception e) {
			throw new JbpmException(e);
		}
	}

	public List<TaskInstance> findTaskInstances(
		long processInstanceId, long tokenId, String[] actorIds,
		boolean pooledActors, Boolean completed, int start, int end,
		OrderByComparator orderByComparator) {

		if ((actorIds != null) && (actorIds.length == 0)) {
			return Collections.EMPTY_LIST;
		}

		try {
			Criteria criteria = _session.createCriteria(TaskInstance.class);

			criteria.setResultTransformer(Criteria.DISTINCT_ROOT_ENTITY);

			if (processInstanceId > 0) {
				criteria.add(
					Restrictions.eq("processInstance.id", processInstanceId));
			}
			else if (tokenId > 0) {
				criteria.add(Restrictions.eq("token.id", tokenId));
			}
			else if (actorIds != null) {
				if (pooledActors) {
					Criteria subcriteria = criteria.createCriteria(
						"pooledActors");

					subcriteria.add(Restrictions.in("actorId", actorIds));

					criteria.add(Restrictions.isNull("actorId"));
				}
				else {
					criteria.add(Restrictions.in("actorId", actorIds));
				}
			}

			if (completed != null) {
				if (completed.booleanValue()) {
					criteria.add(Restrictions.isNotNull("end"));
				}
				else {
					criteria.add(Restrictions.isNull("end"));
				}
			}

			addPagination(criteria, start, end);
			addOrder(criteria, orderByComparator);

			return criteria.list();
		}
		catch (Exception e) {
			throw new JbpmException(e);
		}
	}

	public WorkflowDefinitionExtensionImpl findWorkflowDefinitonExtension(
		long processDefinitionId){

		try {
			Criteria criteria = _session.createCriteria(
				WorkflowDefinitionExtensionImpl.class);

			criteria.add(
				Restrictions.eq("processDefinition.id", processDefinitionId));

			return (WorkflowDefinitionExtensionImpl)criteria.uniqueResult();
		}
		catch (Exception e) {
			throw new JbpmException(e);
		}
	}

	public int searchCountTaskInstances(
		String[] actorIds, Boolean pooledActors, String taskName,
		String assetType, Date dueDateGT, Date dueDateLT, Boolean completed,
		boolean andOperator) {

		try {
			Criteria criteria = _session.createCriteria(TaskInstance.class);

			criteria.setProjection(Projections.countDistinct("id"));

			addSearchCriteria(
				criteria, actorIds, pooledActors, taskName, assetType,
				dueDateGT, dueDateLT, completed, andOperator);

			Number count = (Number)criteria.uniqueResult();

			return count.intValue();
		}
		catch (Exception e) {
			throw new JbpmException(e);
		}
	}

	public int searchCountTaskInstances(
		String[] actorIds, Boolean pooledActors, String[] taskNames,
		Boolean completed) {

		try {
			Criteria criteria = _session.createCriteria(TaskInstance.class);

			criteria.setProjection(Projections.countDistinct("id"));

			addSearchCriteria(
				criteria, actorIds, pooledActors, taskNames, completed);

			Number count = (Number)criteria.uniqueResult();

			return count.intValue();
		}
		catch (Exception e) {
			throw new JbpmException(e);
		}
	}

	public List<TaskInstance> searchTaskInstances(
		String[] actorIds, Boolean pooledActors, String taskName,
		String assetType, Date dueDateGT, Date dueDateLT, Boolean completed,
		boolean andOperator, int start, int end,
		OrderByComparator orderByComparator) {

		try {
			Criteria criteria = _session.createCriteria(TaskInstance.class);

			criteria.setResultTransformer(Criteria.DISTINCT_ROOT_ENTITY);

			addSearchCriteria(
				criteria, actorIds, pooledActors, taskName, assetType,
				dueDateGT, dueDateLT, completed, andOperator);

			addPagination(criteria, start, end);
			addOrder(criteria, orderByComparator);

			return criteria.list();
		}
		catch (Exception e) {
			throw new JbpmException(e);
		}
	}

	public List<TaskInstance> searchTaskInstances(
		String[] actorIds, Boolean pooledActors, String[] taskNames,
		Boolean completed,	int start, int end,
		OrderByComparator orderByComparator) {

		try {
			Criteria criteria = _session.createCriteria(TaskInstance.class);

			criteria.setResultTransformer(Criteria.DISTINCT_ROOT_ENTITY);

			addSearchCriteria(
				criteria, actorIds, pooledActors, taskNames, completed);

			addPagination(criteria, start, end);
			addOrder(criteria, orderByComparator);

			return criteria.list();
		}
		catch (Exception e) {
			throw new JbpmException(e);
		}
	}

	protected void addDisjunction(
		Junction junction, String propertyName, String[] values) {

		Disjunction disjunction = Restrictions.disjunction();

		for (String value : values) {
			disjunction.add(Restrictions.like(propertyName, value));
		}

		junction.add(disjunction);
	}

	protected void addOrder(
		Criteria criteria, OrderByComparator orderByComparator,
		String... skipFields) {

		if (orderByComparator == null) {
			return;
		}

		String[] orderByFields = orderByComparator.getOrderByFields();

		Arrays.sort(skipFields);

		for (String orderByField : orderByFields) {
			Order order = null;

			String jbpmField = _fieldMap.get(orderByField);

			if (jbpmField == null) {
				jbpmField = orderByField;
			}

			if (Arrays.binarySearch(skipFields, jbpmField) < 0) {
				if (orderByComparator.isAscending()) {
					order = Order.asc(jbpmField);
				}
				else {
					order = Order.desc(jbpmField);
				}

				criteria.addOrder(order);
			}
		}
	}

	protected void addPagination(Criteria criteria, int start, int end) {
		if ((start != QueryUtil.ALL_POS) && (end != QueryUtil.ALL_POS)) {
			criteria.setFirstResult(start);
			criteria.setMaxResults(end - start);
		}
	}

	protected void addSearchCriteria(
		Criteria criteria, String[] actorIds, Boolean pooledActors,
		String taskName, String assetType, Date dueDateGT, Date dueDateLT,
		Boolean completed, boolean andOperator) {

		if (actorIds != null) {
			if ((pooledActors != null) && pooledActors.booleanValue()) {
				Criteria subcriteria = criteria.createCriteria(
					"pooledActors");

				subcriteria.add(Restrictions.in("actorId", actorIds));

				criteria.add(Restrictions.isNull("actorId"));
			}
			else {
				criteria.add(Restrictions.in("actorId", actorIds));
			}
		}

		Junction kewordsJunction;

		if (andOperator) {
			kewordsJunction = Restrictions.conjunction();
		}
		else {
			kewordsJunction = Restrictions.disjunction();
		}

		if (taskName != null) {
			kewordsJunction.add(Restrictions.like("name", taskName));
		}

		if (assetType != null){
			criteria.createAlias("processInstance", "processInstance");
			criteria.createAlias("processInstance.instances", "instances");
			criteria.createAlias("instances.tokenVariableMaps", "varMaps");
			criteria.createAlias(
				"varMaps.variableInstances", "varInstances");

			Criterion typeCriterion = Restrictions.and(
				Restrictions.eq(
					"varInstances.name", ContextConstants.ENTRY_TYPE),
				Restrictions.like("varInstances.value", assetType));

			kewordsJunction.add(typeCriterion);
		}

		if ((dueDateGT != null) && (dueDateLT != null)) {
			kewordsJunction.add(
				Restrictions.between("dueDate", dueDateGT, dueDateLT));
		}

		criteria.add(kewordsJunction);

		if (completed != null) {
			if (completed.booleanValue()) {
				criteria.add(Restrictions.isNotNull("end"));
			}
			else {
				criteria.add(Restrictions.isNull("end"));
			}
		}
	}

	protected void addSearchCriteria(
		Criteria criteria, String[] actorIds, Boolean pooledActors,
		String[] taskNames, Boolean completed) {

		if (actorIds != null) {
			if ((pooledActors != null) && pooledActors.booleanValue()) {
				Criteria subcriteria = criteria.createCriteria(
					"pooledActors");

				subcriteria.add(Restrictions.in("actorId", actorIds));

				criteria.add(Restrictions.isNull("actorId"));
			}
			else {
				criteria.add(Restrictions.in("actorId", actorIds));
			}
		}

		Disjunction kewordsDisjunction = Restrictions.disjunction();

		if ((taskNames != null) && (taskNames.length > 0)) {
			addDisjunction(kewordsDisjunction, "name", taskNames);
		}

		criteria.add(kewordsDisjunction);

		if (completed != null) {
			if (completed.booleanValue()) {
				criteria.add(Restrictions.isNotNull("end"));
			}
			else {
				criteria.add(Restrictions.isNull("end"));
			}
		}
	}

	protected ProcessDefinition findProcessDefinition(String name) {
		try {
			Criteria criteria = _session.createCriteria(
				ProcessDefinition.class);

			criteria.add(Restrictions.eq("name", name));

			Order order = Order.desc("version");

			criteria.addOrder(order);

			criteria.setFirstResult(0);
			criteria.setMaxResults(1);

			return (ProcessDefinition)criteria.uniqueResult();
		}
		catch (Exception e) {
			throw new JbpmException(e);
		}
	}

	protected List<ProcessDefinition> findProcessDefinitions(
		List<String> names) {

		try {
			List<ProcessDefinition> processDefinitions =
				new ArrayList<ProcessDefinition>();

			for (String name : names) {
				ProcessDefinition processDefinition = findProcessDefinition(
					name);

				processDefinitions.add(processDefinition);
			}

			return processDefinitions;
		}
		catch (Exception e) {
			throw new JbpmException(e);
		}
	}

	private static Map<String, String> _fieldMap =
		new HashMap<String, String>();

	static {
		_fieldMap.put("completionDate", "end");
		_fieldMap.put("createDate", "create");
		_fieldMap.put("endDate", "end");
		_fieldMap.put("startDate", "start");
		_fieldMap.put("state", "currentNodeName");
		_fieldMap.put("userId", "actorId");
		_fieldMap.put("workflowDefinitionId", "id");
		_fieldMap.put("workflowLogId", "id");
		_fieldMap.put("workflowInstanceId", "id");
		_fieldMap.put("workflowTaskId", "id");
	}

	private Session _session;

}