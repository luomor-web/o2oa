package com.x.query.assemble.surface.queue;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.x.base.core.container.EntityManagerContainer;
import com.x.base.core.container.factory.EntityManagerContainerFactory;
import com.x.base.core.entity.JpaObject;
import com.x.base.core.entity.annotation.CheckPersistType;
import com.x.base.core.entity.dynamic.DynamicEntity;
import com.x.base.core.project.Applications;
import com.x.base.core.project.exception.ExceptionEntityNotExist;
import com.x.base.core.project.gson.XGsonBuilder;
import com.x.base.core.project.jaxrs.WoId;
import com.x.base.core.project.logger.Logger;
import com.x.base.core.project.logger.LoggerFactory;
import com.x.base.core.project.queue.AbstractQueue;
import com.x.base.core.project.tools.ListTools;
import com.x.base.core.project.x_cms_assemble_control;
import com.x.base.core.project.x_processplatform_assemble_surface;
import com.x.processplatform.core.entity.content.WorkLog;
import com.x.query.assemble.surface.ThisApplication;
import com.x.query.core.entity.ImportModel;
import com.x.query.core.entity.ImportRecord;
import com.x.query.core.entity.ImportRecordItem;
import com.x.query.core.entity.schema.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据模板数据导入
 *
 */
public class QueueImportData extends AbstractQueue<String> {

	private static Logger logger = LoggerFactory.getLogger(QueueImportData.class);

	private static Gson gson = XGsonBuilder.instance();

	private static final String PROCESS_STATUS_DRAFT = "draft";

	public void execute( String recordId ) {
		logger.info("开始数据模板数据导入：{}", recordId);
		try {
			ImportRecord record;
			ImportModel model;
			try (EntityManagerContainer emc = EntityManagerContainerFactory.instance().create()) {
				record = emc.find(recordId, ImportRecord.class);
				if(record == null){
					logger.warn("导入记录不存在：{}",recordId);
					return;
				}
				if(record.getStatus().equals(ImportRecord.STATUS_PROCESSING)){
					logger.warn("导入记录正在导入：{}",recordId);
					return;
				}
				if(record.getStatus().equals(ImportRecord.STATUS_SUCCESS)){
					logger.warn("导入记录已成功导入：{}",recordId);
					return;
				}
				model = emc.find(record.getModelId(), ImportModel.class);
				if(model == null){
					logger.warn("导入记录对应的导入模型不存在：{}",record.getModelId());
					return;
				}
				emc.beginTransaction(ImportRecord.class);
				record.setStatus(ImportRecord.STATUS_PROCESSING);
				emc.commit();
			}
			try {
				switch (model.getType()) {
					case ImportModel.TYPE_CMS:
						importCms(record, model);
						break;
					case ImportModel.TYPE_DYNAMIC_TABLE:
						importDynamicTable(record, model);
						break;
					case ImportModel.TYPE_PROCESSPLATFORM:
						importProcessPlatform(record, model);
						break;
				}
			} catch (Exception e) {
				try (EntityManagerContainer emc = EntityManagerContainerFactory.instance().create()) {
					record = emc.find(recordId, ImportRecord.class);
					emc.beginTransaction(ImportRecord.class);
					record.setStatus(ImportRecord.STATUS_FAILED);
					record.setDistribution(e.getMessage());
					emc.commit();
				}
				logger.warn("数据模板数据导入异常：{}", recordId);
				logger.error(e);
			}
		} catch (Exception e) {
			logger.warn("数据模板数据导入异常：{}", recordId);
			logger.error(e);
		}
		logger.info("完成数据模板数据导入：{}", recordId);
	}

	private void importCms(final ImportRecord record, final ImportModel model) throws Exception {
		JsonObject jsonObject = gson.fromJson(model.getData(), JsonObject.class);
		String categoryId = jsonObject.getAsJsonObject("category").get("id").getAsString();
		String documentType = jsonObject.get("documentType").getAsString();
		boolean reImport = false;
		try (EntityManagerContainer emc = EntityManagerContainerFactory.instance().create()) {
			List<ImportRecordItem> itemList = emc.listEqualAndEqual(ImportRecordItem.class, ImportRecordItem.recordId_FIELDNAME, record.getId(),
					ImportRecordItem.status_FIELDNAME, ImportRecordItem.STATUS_FAILED);
			if(ListTools.isNotEmpty(itemList)){
				logger.info("重新导入失败的CMS数据：{}", record.getId());
				reImport = true;
				boolean hasSuccess = false;
				boolean hasFailed = false;
				ImportRecord ir = emc.find(record.getId(), ImportRecord.class);
				emc.beginTransaction(ImportRecord.class);
				emc.beginTransaction(ImportRecordItem.class);
				for (ImportRecordItem item : itemList) {
					JsonObject document = gson.fromJson(item.getData(), JsonObject.class);
					document.addProperty("categoryId", categoryId);
					document.addProperty("documentType", documentType);
					document.addProperty("docStatus", "published");
					document.addProperty("isNotice", false);
					document.remove("srcData");
					try {
						WoId woId = ThisApplication.context().applications().putQuery(x_cms_assemble_control.class,
								Applications.joinQueryUri("document", "publish", "content"), document).getData(WoId.class);
						item.setDocId(woId.getId());
						item.setStatus(ImportRecordItem.STATUS_SUCCESS);
						hasSuccess = true;
					} catch (Exception e) {
						item.setStatus(ImportRecordItem.STATUS_FAILED);
						item.setDistribution(e.getMessage());
						hasFailed = true;
					}
				}
				String status = ImportRecord.STATUS_SUCCESS;
				if(hasFailed){
					if(hasSuccess){
						status = ImportRecord.STATUS_PART_SUCCESS;
					}else{
						status = ImportRecord.STATUS_FAILED;
					}
				}
				ir.setStatus(status);
				ir.setDistribution("");
				emc.commit();
			}
		}
		if(reImport){
			return;
		}
		JsonElement jsonElement = gson.fromJson(record.getData(), JsonElement.class);
		final List<ImportRecordItem> itemList = new ArrayList<>();
		jsonElement.getAsJsonArray().forEach(o -> {
			JsonObject document = o.getAsJsonObject();
			document.addProperty("categoryId", categoryId);
			document.addProperty("documentType", documentType);
			document.addProperty("docStatus", "published");
			document.addProperty("isNotice", false);
			JsonElement srcData = document.get("srcData");
			String title = document.get("title").getAsString();
			ImportRecordItem item = new ImportRecordItem();
			item.setDocTitle(title);
			item.setDocType(model.getType());
			item.setRecordId(record.getId());
			item.setModelId(record.getModelId());
			item.setQuery(record.getQuery());
			if(srcData!=null) {
				item.setSrcData(srcData.toString());
				document.remove("srcData");
			}
			item.setData(document.toString());
			try {
				WoId woId = ThisApplication.context().applications().putQuery(x_cms_assemble_control.class,
						Applications.joinQueryUri("document", "publish", "content"), document).getData(WoId.class);
				item.setDocId(woId.getId());
				item.setStatus(ImportRecordItem.STATUS_SUCCESS);
			} catch (Exception e) {
				item.setStatus(ImportRecordItem.STATUS_FAILED);
				item.setDistribution(e.getMessage());
			}
			itemList.add(item);
		});
		try (EntityManagerContainer emc = EntityManagerContainerFactory.instance().create()) {
			ImportRecord ir = emc.find(record.getId(), ImportRecord.class);
			boolean hasSuccess = false;
			boolean hasFailed = false;
			emc.beginTransaction(ImportRecord.class);
			emc.beginTransaction(ImportRecordItem.class);
			for (ImportRecordItem o : itemList) {
				if(ImportRecordItem.STATUS_FAILED.equals(o.getStatus())){
					hasFailed = true;
				}else{
					hasSuccess = true;
				}
				emc.persist(o, CheckPersistType.all);
			}
			String status = ImportRecord.STATUS_SUCCESS;
			if(hasFailed){
				if(hasSuccess){
					status = ImportRecord.STATUS_PART_SUCCESS;
				}else{
					status = ImportRecord.STATUS_FAILED;
				}
			}
			ir.setStatus(status);
			ir.setDistribution("");
			emc.commit();
		}
	}

	private void importDynamicTable(ImportRecord record, ImportModel model) throws Exception {
		JsonElement jsonElement = gson.fromJson(record.getData(), JsonElement.class);
		JsonObject jsonObject = gson.fromJson(model.getData(), JsonObject.class);
		String tableId = jsonObject.getAsJsonObject("dynamicTable").get("id").getAsString();
		try (EntityManagerContainer emc = EntityManagerContainerFactory.instance().create()) {
			ImportRecord ir = emc.find(record.getId(), ImportRecord.class);
			Table table = emc.flag(tableId, Table.class);
			if (null == table) {
				throw new ExceptionEntityNotExist(tableId, Table.class);
			}
			DynamicEntity dynamicEntity = new DynamicEntity(table.getName());
			@SuppressWarnings("unchecked")
			Class<? extends JpaObject> cls = (Class<JpaObject>) Class.forName(dynamicEntity.className());
			List<Object> os = new ArrayList<>();
			if (jsonElement.isJsonArray()) {
				jsonElement.getAsJsonArray().forEach(o -> os.add(gson.fromJson(o, cls)));
			} else if (jsonElement.isJsonObject()) {
				os.add(gson.fromJson(jsonElement, cls));
			}
			emc.beginTransaction(ImportRecord.class);
			emc.beginTransaction(cls);
			for (Object o : os) {
				emc.persist((JpaObject) o, CheckPersistType.all);
			}
			ir.setStatus(ImportRecord.STATUS_SUCCESS);
			emc.commit();
		}
	}

	private void importProcessPlatform(ImportRecord record, ImportModel model) throws Exception {
		JsonObject jsonObject = gson.fromJson(model.getData(), JsonObject.class);
		String processId = jsonObject.getAsJsonObject("process").get("id").getAsString();
		String processStatus = jsonObject.get("processStatus").getAsString();
		boolean reImport = false;
		try (EntityManagerContainer emc = EntityManagerContainerFactory.instance().create()) {
			List<ImportRecordItem> itemList = emc.listEqualAndEqual(ImportRecordItem.class, ImportRecordItem.recordId_FIELDNAME, record.getId(),
					ImportRecordItem.status_FIELDNAME, ImportRecordItem.STATUS_FAILED);
			if(ListTools.isNotEmpty(itemList)){
				logger.info("重新导入失败的流程工单数据：{}", record.getId());
				reImport = true;
				boolean hasSuccess = false;
				boolean hasFailed = false;
				ImportRecord ir = emc.find(record.getId(), ImportRecord.class);
				emc.beginTransaction(ImportRecord.class);
				emc.beginTransaction(ImportRecordItem.class);
				for (ImportRecordItem item : itemList) {
					JsonObject document = gson.fromJson(item.getData(), JsonObject.class);
					document.remove("srcData");
					try {
						if(PROCESS_STATUS_DRAFT.equals(processStatus)) {
							List<WorkLog> workLogList = ThisApplication.context().applications().postQuery(x_processplatform_assemble_surface.class,
									Applications.joinQueryUri("work", "process", processId), document).getDataAsList(WorkLog.class);
							item.setDocId(workLogList.get(0).getWork());
							item.setStatus(ImportRecordItem.STATUS_SUCCESS);
							hasSuccess = true;
						}else{
							WoId woId = ThisApplication.context().applications().putQuery(x_processplatform_assemble_surface.class,
									Applications.joinQueryUri("workcompleted", "process", processId), document).getData(WoId.class);
							item.setDocId(woId.getId());
							item.setStatus(ImportRecordItem.STATUS_SUCCESS);
							hasSuccess = true;
						}
					} catch (Exception e) {
						item.setStatus(ImportRecordItem.STATUS_FAILED);
						item.setDistribution(e.getMessage());
						hasFailed = true;
					}
				}
				String status = ImportRecord.STATUS_SUCCESS;
				if(hasFailed){
					if(hasSuccess){
						status = ImportRecord.STATUS_PART_SUCCESS;
					}else{
						status = ImportRecord.STATUS_FAILED;
					}
				}
				ir.setStatus(status);
				ir.setDistribution("");
				emc.commit();
			}
		}
		if(reImport){
			return;
		}
		JsonElement jsonElement = gson.fromJson(record.getData(), JsonElement.class);
		final List<ImportRecordItem> itemList = new ArrayList<>();
		jsonElement.getAsJsonArray().forEach(o -> {
			JsonObject document = o.getAsJsonObject();
			JsonElement srcData = document.get("srcData");
			String title = document.get("title").getAsString();
			ImportRecordItem item = new ImportRecordItem();
			item.setDocTitle(title);
			item.setDocType(model.getType());
			item.setRecordId(record.getId());
			item.setModelId(record.getModelId());
			item.setQuery(record.getQuery());
			if(srcData!=null) {
				item.setSrcData(srcData.toString());
				document.remove("srcData");
			}
			item.setData(document.toString());
			try {
				if(PROCESS_STATUS_DRAFT.equals(processStatus)) {
					List<WorkLog> workLogList = ThisApplication.context().applications().postQuery(x_processplatform_assemble_surface.class,
							Applications.joinQueryUri("work", "process", processId), document).getDataAsList(WorkLog.class);
					item.setDocId(workLogList.get(0).getWork());
					item.setStatus(ImportRecordItem.STATUS_SUCCESS);
				}else{
					WoId woId = ThisApplication.context().applications().putQuery(x_processplatform_assemble_surface.class,
							Applications.joinQueryUri("workcompleted", "process", processId), document).getData(WoId.class);
					item.setDocId(woId.getId());
					item.setStatus(ImportRecordItem.STATUS_SUCCESS);
				}
			} catch (Exception e) {
				item.setStatus(ImportRecordItem.STATUS_FAILED);
				item.setDistribution(e.getMessage());
			}
			itemList.add(item);
		});
		try (EntityManagerContainer emc = EntityManagerContainerFactory.instance().create()) {
			ImportRecord ir = emc.find(record.getId(), ImportRecord.class);
			boolean hasSuccess = false;
			boolean hasFailed = false;
			emc.beginTransaction(ImportRecord.class);
			emc.beginTransaction(ImportRecordItem.class);
			for (ImportRecordItem o : itemList) {
				if(ImportRecordItem.STATUS_FAILED.equals(o.getStatus())){
					hasFailed = true;
				}else{
					hasSuccess = true;
				}
				emc.persist(o, CheckPersistType.all);
			}
			String status = ImportRecord.STATUS_SUCCESS;
			if(hasFailed){
				if(hasSuccess){
					status = ImportRecord.STATUS_PART_SUCCESS;
				}else{
					status = ImportRecord.STATUS_FAILED;
				}
			}
			ir.setStatus(status);
			ir.setDistribution("");
			emc.commit();
		}
	}
}
