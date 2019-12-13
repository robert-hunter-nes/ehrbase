/*
 * Copyright (c) 2019 Vitasystems GmbH, Hannover Medical School, and Luis Marco-Ruiz (Hannover Medical School).
 *
 * This file is part of project EHRbase
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ehrbase.dao.access.jooq;

import com.nedap.archie.rm.datastructures.ItemStructure;
import com.nedap.archie.rm.directory.Folder;
import com.nedap.archie.rm.support.identification.ObjectId;
import com.nedap.archie.rm.support.identification.ObjectRef;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ehrbase.api.exception.InternalServerException;
import org.ehrbase.api.exception.ObjectNotFoundException;
import org.ehrbase.dao.access.interfaces.I_ConceptAccess;
import org.ehrbase.dao.access.interfaces.I_ContributionAccess;
import org.ehrbase.dao.access.interfaces.I_DomainAccess;
import org.ehrbase.dao.access.interfaces.I_FolderAccess;
import org.ehrbase.dao.access.support.DataAccess;
import org.ehrbase.dao.access.util.ContributionDef;
import org.ehrbase.dao.access.util.FolderUtils;
import org.ehrbase.jooq.pg.enums.ContributionDataType;
import org.ehrbase.jooq.pg.tables.FolderHierarchy;
import org.ehrbase.jooq.pg.tables.records.FolderHierarchyRecord;
import org.ehrbase.jooq.pg.tables.records.FolderItemsRecord;
import org.ehrbase.jooq.pg.tables.records.FolderRecord;
import org.ehrbase.jooq.pg.tables.records.ObjectRefRecord;
import org.joda.time.DateTime;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.postgresql.util.PGobject;

import java.sql.Timestamp;
import java.util.*;

import static org.ehrbase.jooq.pg.Tables.*;
import static org.jooq.impl.DSL.*;

/***
 *@Created by Luis Marco-Ruiz on Jun 13, 2019
 */
public class FolderAccessHistory extends DataAccess implements I_FolderAccess, Comparable<FolderAccessHistory> {

    private static final Logger log = LogManager.getLogger(FolderAccessHistory.class);

    // TODO: Check how to remove this unused details for confusion prevention
    private ItemStructure details;

    private List<ObjectRef> items = new ArrayList<>();
    private Map<UUID, I_FolderAccess> subfoldersList = new TreeMap<>();
    private I_ContributionAccess contributionAccess;
    private UUID ehrId;
    private FolderRecord folderRecord;

    /********Constructors*******/

    public FolderAccessHistory(I_DomainAccess domainAccess) {
        super(domainAccess);
        this.folderRecord = getContext().newRecord(org.ehrbase.jooq.pg.tables.Folder.FOLDER);

        //associate a contribution with this composition
        this.contributionAccess = I_ContributionAccess.getInstance(this, this.ehrId);
        this.contributionAccess.setState(ContributionDef.ContributionState.COMPLETE);
    }

    public FolderAccessHistory(I_DomainAccess domainAccess, UUID ehrId, I_ContributionAccess contributionAccess) {
        super(domainAccess);
        this.ehrId=ehrId;
        this.folderRecord = getContext().newRecord(org.ehrbase.jooq.pg.tables.Folder.FOLDER);
        this.contributionAccess = contributionAccess;
        //associate a contribution with this composition, if needed.
        if(contributionAccess == null){
            this.contributionAccess = I_ContributionAccess.getInstance(this, this.ehrId);
        }
        UUID ehrIdLoc = this.contributionAccess.getEhrId();
        this.contributionAccess.setState(ContributionDef.ContributionState.COMPLETE);
    }

    /*************Data Access and modification methods*****************/

    @Override
    public Boolean update(Timestamp transactionTime) {
        return this.update(transactionTime, true);
    }

    @Override
    public Boolean update(final Timestamp transactionTime, final boolean force){
        /*create new contribution*/
        UUID old_contribution = this.folderRecord.getInContribution();
        UUID new_contribution = this.folderRecord.getInContribution();

        UUID ehrId =this.contributionAccess.getEhrId();
        /*save the EHR id from old_contribution since it will be the same as this is an update operation*/
        if(this.contributionAccess.getEhrId() == null){
            final Record1<UUID>  result1= getContext().select(CONTRIBUTION.EHR_ID).from(CONTRIBUTION).where(CONTRIBUTION.ID.eq(old_contribution)).fetch().get(0);
            ehrId = result1.value1();
        }
        this.contributionAccess.setEhrId(ehrId);

        this.contributionAccess.commit(transactionTime, null, null, ContributionDataType.folder, ContributionDef.ContributionState.COMPLETE, I_ConceptAccess.ContributionChangeType.MODIFICATION, null);
        this.getFolderRecord().setInContribution(this.contributionAccess.getId());
        new_contribution = folderRecord.getInContribution();

        // Delete so folder can be overwritten
        // This will also delete items since cascading the delete to the items table as well as
        // all FolderHierarchy entires
        this.delete(folderRecord.getId());

        return this.update(transactionTime, true, true, null, old_contribution, new_contribution);
    }

    private Boolean update(final Timestamp transactionTime,
                           final boolean force,
                           boolean rootFolder,
                           UUID parentFolder,
                           UUID oldContribution,
                           UUID newContribution) {

        boolean result = false;

        DSLContext dslContext = getContext();
        dslContext.attach(this.folderRecord);

        // Set new Contribution for MODIFY
        this.setInContribution(newContribution);

        // Copy into new instance and attach to DB context.
        // The new instance is required to store the new record with a new ID
        FolderRecord updatedFolderRecord = new FolderRecord();
        updatedFolderRecord.setInContribution(newContribution);
        updatedFolderRecord.setName(this.getFolderName());
        updatedFolderRecord.setArchetypeNodeId(this.getFolderArchetypeNodeId());
        updatedFolderRecord.setActive(this.isFolderActive());
        updatedFolderRecord.setDetails(this.getFolderDetails());
        updatedFolderRecord.setSysTransaction(transactionTime);
        updatedFolderRecord.setSysPeriod(PGObjectParser.parseSysPeriod(this.getFolderSysPeriod()));

        // attach to context DB
        dslContext.attach(updatedFolderRecord);

        // Save new Folder entry to the database
        result = updatedFolderRecord.store() > 0;
        // Get new folder id for folder items and hierarchy
        UUID updatedFolderId = updatedFolderRecord.getId();

        // Update items -> Save new list of all items in this folder
        this.saveFolderItems(updatedFolderId,
                             oldContribution,
                             newContribution,
                             transactionTime,
                             getContext());

        // Create FolderHierarchy entries if this instance is a sub folder
        if (!rootFolder) {
            FolderHierarchyRecord updatedFhR = new FolderHierarchyRecord();
            updatedFhR.setParentFolder(parentFolder);
            updatedFhR.setChildFolder(updatedFolderId);
            updatedFhR.setInContribution(newContribution);
            updatedFhR.setSysTransaction(transactionTime);
            updatedFhR.setSysPeriod(PGObjectParser.parseSysPeriod(folderRecord.getSysPeriod()));
            dslContext.attach(updatedFhR);
            updatedFhR.store();
        }

        boolean anySubfolderModified = this.getSubfoldersList() // Map of sub folders with UUID
                                           .values() // Get all I_FolderAccess entries
                                           .stream() // Iterate over the I_FolderAccess entries
                                           .anyMatch(subfolder -> ( // Update each entry and return if there has been at least one entry updated
                                                   ((FolderAccessHistory) subfolder).update(transactionTime,
                                                                                     force,
                                                                                     false,
                                                                                     updatedFolderId,
                                                                                     oldContribution,
                                                                                     newContribution)
                                           ));

        // Finally overwrite original FolderRecord on this FolderAccess instance to have the
        // new data available at service layer. Thus we do not need to re-fetch the updated folder
        // tree from DB
        this.folderRecord = updatedFolderRecord;
        return result || anySubfolderModified;
    }

    private void saveFolderItems(final UUID folderId, final UUID old_contribution, final UUID new_contribution, final Timestamp transactionTime, DSLContext context){

        for(ObjectRef or : this.getItems()){

            //insert in object_ref
            ObjectRefRecord orr = new ObjectRefRecord(or.getNamespace(), or.getType(),UUID.fromString( or.getId().getValue()), new_contribution, transactionTime, PGObjectParser.parseSysPeriod(folderRecord.getSysPeriod()));
            context.attach(orr);
            orr.store();

            //insert in folder_item
            FolderItemsRecord fir = new FolderItemsRecord(folderId, UUID.fromString(or.getId().getValue()), new_contribution, transactionTime, PGObjectParser.parseSysPeriod(folderRecord.getSysPeriod()));
            context.attach(fir);
            fir.store();
        }
    }

    @Override
    public Boolean update() {
        return  this.update(new Timestamp(DateTime.now().getMillis()), true);
    }

    @Override
    public Boolean update(Boolean force){
        return  this.update(new Timestamp(DateTime.now().getMillis()), force);
    }

    @Override
    public Integer delete(){
        return this.delete(this.getFolderId());
    }

    @Override
    public UUID commit(Timestamp transactionTime){
        // Create Contribution entry for all folders
        this.contributionAccess.commit(
                transactionTime,
                null,
                null,
                ContributionDataType.folder,
                ContributionDef.ContributionState.COMPLETE,
                I_ConceptAccess.ContributionChangeType.CREATION,
                null
        );
        this.getFolderRecord().setInContribution(this.contributionAccess.getId());

        // Save the folder record to database
        this.getFolderRecord().store();

        //Save folder items
        this.saveFolderItems(this.getFolderRecord().getId(), this.contributionAccess.getContributionId(), this.contributionAccess.getContributionId(), new Timestamp(DateTime.now().getMillis()), getContext());

        // Save list of sub folders to database with parent <-> child ID relations
        this.getSubfoldersList().forEach((child_id, child) -> {
            child.commit();
            FolderHierarchyRecord fhRecord = this.buildFolderHierarchyRecord(
                    this.getFolderRecord().getId(),
                    ((FolderAccessHistory)child).getFolderRecord().getId(),
                    this.contributionAccess.getId(),
                    new Timestamp(DateTime.now().getMillis()),
                    null
            );
            fhRecord.store();
        });
        return this.getFolderRecord().getId();
    }

    @Override
    public UUID commit(){
        Timestamp timestamp = new Timestamp(DateTime.now().getMillis());
        return this.commit(timestamp);
    }

    /**
     * Retrieve instance of {@link I_FolderAccess} with the information needed retrieve the folder and its sub-folders.
     * @param domainAccess providing the information about the DB connection.
     * @param folderId {@link UUID} of the {@link  Folder} to be fetched from the DB.
     * @return the {@link I_FolderAccess} that provides DB access to the {@link  Folder} that corresponds to the provided folderId param.
     * @throws Exception
     */
    public static I_FolderAccess retrieveInstanceForExistingFolder(I_DomainAccess domainAccess, UUID folderId){

        /***1-retrieve CTE as a table that contains all the rows that allow to infer each parent-child relationship***/
        FolderHierarchy sf =  FOLDER_HIERARCHY.as("sf");

        Table<?> sf_table = table(
                select()
                        .from(FOLDER_HIERARCHY));

        Table<?> folder_table = table(
                select()
                        .from(FOLDER)).as("t_folder1");
        Table<?> folder_table2 = table(
                select()
                        .from(FOLDER)).as("t_folder2");

        Table<?> initial_table = table(
                select()
                        .from(FOLDER_HIERARCHY)
                        .where(
                                FOLDER_HIERARCHY.PARENT_FOLDER.eq(folderId)));

        Field<UUID> subfolderChildFolder = field("subfolders.{0}", FOLDER_HIERARCHY.CHILD_FOLDER.getDataType(), FOLDER_HIERARCHY.CHILD_FOLDER.getUnqualifiedName());
        Field<UUID> subfolderParentFolderRef = field(name("subfolders", "parent_folder"), UUID.class);
        Result<Record> folderSelectedRecordSub = domainAccess.getContext().withRecursive("subfolders").as(
                select().
                        from(initial_table).
                        leftJoin(folder_table).on(initial_table.field("parent_folder", FOLDER_HIERARCHY.PARENT_FOLDER.getType()).eq(
                        folder_table.field("id", FOLDER.ID.getType()))).
                        union(
                                (select().from(sf_table).
                                        innerJoin("subfolders").on(sf_table.field("parent_folder", FOLDER_HIERARCHY.PARENT_FOLDER.getType()).
                                        eq(subfolderChildFolder))).leftJoin(folder_table2).on(
                                        folder_table2.field("id", FOLDER.ID.getType()).eq(subfolderChildFolder)))
        ).select().from(table(name("subfolders"))).fetch();

        /**2-Reconstruct hierarchical structure from DB result**/
        Map<UUID, Map<UUID, I_FolderAccess>> fHierarchyMap = new TreeMap<UUID, Map<UUID, I_FolderAccess>>();
        for(Record record : folderSelectedRecordSub){

            //1-create a folder access for the record if needed
            if(!fHierarchyMap.containsKey((UUID) record.getValue("parent_folder"))){
                fHierarchyMap.put((UUID) record.getValue("parent_folder"), new TreeMap<>());
            }
            fHierarchyMap.get(record.getValue("parent_folder")).put((UUID) record.getValue("child_folder"), buildFolderAccessFromFolderId((UUID)record.getValue("child_folder"), domainAccess, folderSelectedRecordSub));
        }

        /**3-populate result and return**/
        return FolderAccessHistory.buildFolderAccessHierarchy(fHierarchyMap, folderId, null, folderSelectedRecordSub, domainAccess);
    }

    /**
     * Builds the {@link I_FolderAccess} for persisting the {@link  Folder} provided as param.
     * @param domainAccess providing the information about the DB connection.
     * @param folder to define the {@link I_FolderAccess} that allows its DB access.
     * @param dateTime that will be set as transaction date when the {@link  Folder} is persisted
     * @param ehrId of the {@link com.nedap.archie.rm.ehr.Ehr} that references the {@link  Folder} provided as param.
     * @return {@link I_FolderAccess} with the information to persist the provided {@link  Folder}
     */
    public static I_FolderAccess getNewFolderAccessInstance(final  I_DomainAccess domainAccess, final  Folder folder, final  DateTime dateTime, final  UUID ehrId){
        return buildFolderAccessTreeRecursively(domainAccess, folder, null, dateTime, ehrId, null);
    }

    /**
     * Deletes the FOLDER identified with the Folder.id provided and all its subfolders recursively.
     * @param folderId of the {@link  Folder} to delete.
     * @return number of the total {@link  Folder} deleted recursively.
     */
    private Integer delete(final UUID folderId){

        if(folderId==null){
            throw new IllegalArgumentException("The folder UID provided for performing a delete operation cannot be null.");
        }

        /**SQL code for the recursive call generated inside the delete that retrieves children iteratively.
         * WITH RECURSIVE subfolders AS (
         * 		SELECT parent_folder, child_folder, in_contribution, sys_transaction
         * 		FROM ehr.folder_hierarchy
         * 		WHERE parent_folder = '00550555-ec91-4025-838d-09ddb4e999cb'
         * 	UNION
         * 		SELECT sf.parent_folder, sf.child_folder, sf.in_contribution, sf.sys_transaction
         * 		FROM ehr.folder_hierarchy sf
         * 		INNER JOIN subfolders s ON sf.parent_folder=s.child_folder
         * ) SELECT * FROM subfolders
         */
        int result;

        Table<?> sf_table = table(
                select()
                        .from(FOLDER_HIERARCHY));

        Table<?> initial_table = table(
                select()
                        .from(FOLDER_HIERARCHY)
                        .where(
                                FOLDER_HIERARCHY.PARENT_FOLDER.eq(folderId)));

        Field<UUID> subfolderChildFolder = field("subfolders.{0}", FOLDER_HIERARCHY.CHILD_FOLDER.getDataType(), FOLDER_HIERARCHY.CHILD_FOLDER.getUnqualifiedName());

        result = this.getContext().delete(FOLDER).where(FOLDER.ID.in(this.getContext().withRecursive("subfolders").as(
                select().
                        from(initial_table).
                        union(
                                (select().from(sf_table).
                                        innerJoin("subfolders").on(sf_table.field("parent_folder", FOLDER_HIERARCHY.PARENT_FOLDER.getType()).
                                        eq(subfolderChildFolder))))
                ).select()
                        .from(table(name("subfolders")))
                        .fetch()
                        .getValues(field(name("child_folder")))
        ))
                .or(FOLDER.ID.eq(folderId))
                .execute();

        return result;
    }


    /**
     * Create a new FolderAccess that contains the full hierarchy of its corresponding {@link I_FolderAccess} children that represents the subfolders.
     * @param fHierarchyMap {@link Map} containing as key the UUID of each Folder, and as value an internal Map. For the internal Map the key is the the UUID of a child {@link  Folder}, and the value is the {@link I_FolderAccess} for enabling DB access to this child.
     * @param currentFolder {@link UUID} of the current {@link  Folder} to treat in the current recursive call of the method.
     * @param parentFa the parent {@link I_FolderAccess} that corresponds to the parent  {@link  Folder} of the {@link  Folder} identified as current.
     * @param folderSelectedRecordSub {@link Result} containing the Records that represent the rows to retrieve from the DB corresponding to the children hierarchy.
     * @param domainAccess containing the information of the DB connection.
     * @return I_FolderAccess populated with its appropriate subfolders as FolderAccess objects.
     * @throws Exception
     */
    private static I_FolderAccess buildFolderAccessHierarchy(final Map<UUID, Map<UUID, I_FolderAccess>> fHierarchyMap, final UUID currentFolder, final I_FolderAccess parentFa, final Result<Record> folderSelectedRecordSub, final I_DomainAccess domainAccess){
        if ((parentFa != null) && (parentFa.getSubfoldersList().keySet().contains(currentFolder))){
            return parentFa.getSubfoldersList().get(currentFolder);
        }
        I_FolderAccess folderAccess = buildFolderAccessFromFolderId(currentFolder, domainAccess, folderSelectedRecordSub);
        if (parentFa != null) {
            parentFa.getSubfoldersList().put(currentFolder, folderAccess);
        }
        if (fHierarchyMap.get(currentFolder) != null) {//if not leave node call children

            for (UUID newChild : fHierarchyMap.get(currentFolder).keySet()) {
                buildFolderAccessHierarchy(fHierarchyMap, newChild, folderAccess, folderSelectedRecordSub, domainAccess);
            }
        }
        return folderAccess;
    }

    /**
     * Create a new {@link FolderAccessHistory} from a {@link Record} DB record
     *
     * @param record_      record containing all the information to build one folder-subfolder relationship.
     * @param domainAccess containing the DB connection information.
     * @return FolderAccess instance
     */
    private static FolderAccessHistory buildFolderAccessFromGenericRecord(final Record record_,
                                                                          final I_DomainAccess domainAccess) {

        Record13<UUID, UUID, UUID, Timestamp, Object, UUID, UUID, String, String, Boolean, PGobject, Timestamp, Timestamp>
                record
                = (Record13<UUID, UUID, UUID, Timestamp, Object, UUID, UUID, String, String, Boolean, PGobject, Timestamp, Timestamp>) record_;
        FolderAccessHistory folderAccess = new FolderAccessHistory(domainAccess);
        folderAccess.folderRecord = new FolderRecord();
        folderAccess.setFolderId(record.value1());
        folderAccess.setInContribution(record.value7());
        folderAccess.setFolderName(record.value8());
        folderAccess.setFolderNArchetypeNodeId(record.value9());
        folderAccess.setIsFolderActive(record.value10());
        // Due to generic type from JOIN The ItemStructure binding does not cover the details
        // and we have to parse it from PGobject manually
        folderAccess.setFolderDetails(FolderUtils.parseFromPGobject(record.value11()));
        folderAccess.setFolderSysTransaction(record.value12());
        folderAccess.setFolderSysPeriod(record.value13());
        folderAccess.getItems()
                    .addAll(FolderAccessHistory.retrieveItemsByFolderAndContributionId(record.value1(),
                                                                                record.value7(),
                                                                                domainAccess));

        return folderAccess;
    }

    /**
     * Create a new FolderAccess from a {@link FolderRecord} DB record
     *
     * @param record_      containing the information of a {@link  Folder} in the DB.
     * @param domainAccess containing the DB connection information.
     * @return FolderAccess instance corresponding to the org.ehrbase.jooq.pg.tables.records.FolderRecord provided.
     */
    private static FolderAccessHistory buildFolderAccessFromFolderRecord(final FolderRecord record_,
                                                                         final I_DomainAccess domainAccess) {
        FolderRecord record = record_;
        FolderAccessHistory folderAccess = new FolderAccessHistory(domainAccess);
        folderAccess.folderRecord = new FolderRecord();
        folderAccess.setFolderId(record.getId());
        folderAccess.setInContribution(record.getInContribution());
        folderAccess.setFolderName(record.getName());
        folderAccess.setFolderNArchetypeNodeId(record.getArchetypeNodeId());
        folderAccess.setIsFolderActive(record.getActive());
        folderAccess.setFolderDetails(record.getDetails());
        folderAccess.setFolderSysTransaction(record.getSysTransaction());
        folderAccess.setFolderSysPeriod(record.getSysPeriod());
        folderAccess.getItems()
                    .addAll(FolderAccessHistory.retrieveItemsByFolderAndContributionId(record.getId(),
                                                                                record.getInContribution(),
                                                                                domainAccess));
        return folderAccess;
    }

    /**
     * Given a UUID for a folder creates the corresponding FolderAccess from the information conveyed by the {@link Result} provided. Alternatively queries the DB if the information needed is not in {@link Result}.
     * * @param id of the folder to define a {@link FolderAccessHistory} from.
     * * @param {@link Result} containing the Records that represent the rows to retrieve from the DB corresponding to the children hierarchy.
     * @return a FolderAccess corresponding to the Folder id provided
     */
    private static FolderAccessHistory buildFolderAccessFromFolderId(final UUID id, final I_DomainAccess domainAccess, final Result<Record> folderSelectedRecordSub){

        for(Record record : folderSelectedRecordSub){
            //if the FOLDER items were returned in the recursive query use them and avoid a DB transaction
            if(record.getValue("parent_folder").equals(id)){

                return buildFolderAccessFromGenericRecord(record, domainAccess);
            }
        }

        //if no data from the Folder has been already recovered for the id of the folder, then query the DB for it.
        FolderRecord folderSelectedRecord = domainAccess.getContext().selectFrom(FOLDER).where(FOLDER.ID.eq(id)).fetchOne();

        if (folderSelectedRecord == null || folderSelectedRecord.size() < 1) {
            throw new ObjectNotFoundException(
                    "folder", "Folder with id " + id + " could not be found"
            );
        }


        return buildFolderAccessFromFolderRecord(folderSelectedRecord, domainAccess);
    }

    /**
     * Builds the FolderAccess with the collection of subfolders empty.
     * @param domainAccess providing the information about the DB connection.
     * @param folder to define a corresponding {@link I_FolderAccess} for allowing its persistence.
     * @param dateTime that will be set as transaction date when the {@link  Folder} is persisted
     * @param ehrId of the {@link com.nedap.archie.rm.ehr.Ehr} that references this {@link  Folder}
     * @return {@link I_FolderAccess} with the information to persist the provided {@link  Folder}
     */
    public static I_FolderAccess buildPlainFolderAccess(final  I_DomainAccess domainAccess, final Folder folder, final  DateTime dateTime, final  UUID ehrId, final I_ContributionAccess contributionAccess){

        FolderAccessHistory folderAccessInstance = new FolderAccessHistory(domainAccess, ehrId, contributionAccess);
        folderAccessInstance.setEhrId(ehrId);
        // In case of creation we have no folderId since it will be created from DB
        if (folder.getUid() != null) {
            folderAccessInstance.setFolderId(UUID.fromString(folder.getUid().getValue()));
        }
        folderAccessInstance.setInContribution(folderAccessInstance.getContributionAccess().getId());
        folderAccessInstance.setFolderName(folder.getName().getValue());
        folderAccessInstance.setFolderNArchetypeNodeId(folder.getArchetypeNodeId());
        folderAccessInstance.setIsFolderActive(true);

        // TODO: Are these guards required?
        if (folder.getDetails() != null) {
            folderAccessInstance.setFolderDetails(folder.getDetails());
        }

        if(folder.getItems() != null && !folder.getItems().isEmpty()){
            folderAccessInstance.getItems().addAll(folder.getItems());
        }

        folderAccessInstance.setFolderSysTransaction(new Timestamp(DateTime.now().getMillis()));
        return folderAccessInstance;
    }

    /**
     * Retrieves a list containing the items as ObjectRefs of the folder corresponding to the id provided.
     * @param folderId of the FOLDER that the items correspond to.
     * @param in_contribution contribution that establishes the reference between a FOLDER and its item.
     * @param domainAccess connection DB data.
     * @return
     */
    private static List<ObjectRef> retrieveItemsByFolderAndContributionId(UUID folderId, UUID in_contribution, I_DomainAccess domainAccess){
        Result<Record> retrievedRecords = domainAccess.getContext().with("folderItemsSelect").as(
                select(FOLDER_ITEMS.OBJECT_REF_ID.as("object_ref_id"), FOLDER_ITEMS.IN_CONTRIBUTION.as("item_in_contribution"))
                        .from(FOLDER_ITEMS)
                        .where(FOLDER_ITEMS.FOLDER_ID.eq(folderId)))
                .select()
                .from(OBJECT_REF, table(name("folderItemsSelect")))

                .where(field(name("object_ref_id"), FOLDER_ITEMS.OBJECT_REF_ID.getType()).eq(OBJECT_REF.ID)
                        .and(field(name("item_in_contribution"), FOLDER_ITEMS.IN_CONTRIBUTION.getType()).eq(OBJECT_REF.IN_CONTRIBUTION))).fetch();


        List<ObjectRef> result = new ArrayList<>();
        for(Record recordRecord : retrievedRecords){
            Record8<String, String, UUID, UUID, Timestamp, Object, UUID, UUID>  recordParam =  (Record8<String, String, UUID, UUID, Timestamp, Object, UUID, UUID>) recordRecord;
            ObjectRefRecord objectRef = new ObjectRefRecord();
            objectRef.setIdNamespace(recordParam.value1());
            objectRef.setType(recordParam.value2());
            objectRef.setId(recordParam.value3());
            objectRef.setInContribution(recordParam.value4());
            objectRef.setSysTransaction(recordParam.value5());
            objectRef.setSysPeriod(recordParam.value6());
            objectRef.setId(recordParam.value7());
            result.add(parseObjectRefRecordIntoObjectRef(objectRef, domainAccess));
        }
        return result;
    }

    /**
     * Transforms a ObjectRef DB record into a Reference Model object.
     * @param objectRefRecord
     * @param domainAccess
     * @return the reference model object.
     */
    private static  ObjectRef parseObjectRefRecordIntoObjectRef(ObjectRefRecord objectRefRecord, I_DomainAccess domainAccess){
        ObjectRef result = new ObjectRef();
        ObjectRefId oref = new FolderAccessHistory(domainAccess).new ObjectRefId(objectRefRecord.getId().toString());
        result.setId(oref);
        result.setType(objectRefRecord.getType());
        result.setNamespace(objectRefRecord.getIdNamespace());
        return result;
    }


    /**
     * Recursive method for populating the hierarchy of {@link I_FolderAccess}  for a given {@link  Folder}.
     * @param domainAccess providing the information about the DB connection.
     * @param current {@link  Folder} explored in the current iteration.
     * @param parent folder of the {@link  Folder} procided as the current parameter.
     * @param dateTime of the transaction that will be stored inthe DB.
     * @param ehrId of the {@link com.nedap.archie.rm.ehr.Ehr} referencing the current {@link  Folder}.
     * @param contributionAccess that corresponds to the contribution that the {@link  Folder} refers to.
     * @return {@link I_FolderAccess} with the complete hierarchy of sub-folders represented as {@link I_FolderAccess}.
     * @throws Exception
     */
    private static I_FolderAccess buildFolderAccessTreeRecursively(final  I_DomainAccess domainAccess, final Folder current, final FolderAccessHistory parent, final  DateTime dateTime, final  UUID ehrId, final I_ContributionAccess contributionAccess) {
        I_FolderAccess folderAccess = null;

        //if the parent already contains the FolderAccess for the specified folder return the corresponding FolderAccess
        if((parent!= null) && (parent.getSubfoldersList().containsKey(UUID.fromString(current.getUid().getValue())))){
            return parent.getSubfoldersList().get(current.getUid());
        }
        //create the corresponding FolderAccess for the current folder
        folderAccess = FolderAccessHistory.buildPlainFolderAccess(domainAccess, current, DateTime.now(), ehrId, contributionAccess);
        //add to parent subfolder list
        if(parent!= null){
            parent.getSubfoldersList().put(((FolderAccessHistory)folderAccess).getFolderRecord().getId(), folderAccess);
        }
        for(Folder child : current.getFolders()){
            buildFolderAccessTreeRecursively(domainAccess, child, (FolderAccessHistory) folderAccess, dateTime, ehrId, ((FolderAccessHistory) folderAccess).getContributionAccess());
        }
        return folderAccess;
    }

    /**
     * Builds a folderAccess hierarchy recursively by iterating over all sub folders of given folder
     * instance. This works for all folders, i.e. from root for an insert as well for a sub folder
     * hierarchy for update.
     *
     * @param domainAccess       - DB connection context
     * @param folder             - Folder to create access for
     * @param timeStamp          - Current time for transaction audit
     * @param ehrId              - Corresponding EHR
     * @param contributionAccess - Contribution instance for creation of all folders
     * @return FolderAccess instance for folder
     */
    public static I_FolderAccess buildNewFolderAccessHierarchy(final I_DomainAccess domainAccess,
                                                               final Folder folder,
                                                               final DateTime timeStamp,
                                                               final UUID ehrId,
                                                               final I_ContributionAccess contributionAccess) {
        // Create access for the current folder
        I_FolderAccess folderAccess = buildPlainFolderAccess(domainAccess,
                                                             folder,
                                                             timeStamp,
                                                             ehrId,
                                                             contributionAccess);

        if (folder.getFolders() != null &&
            !folder.getFolders()
                   .isEmpty()) {
            // Iterate over sub folders and create FolderAccess for each sub folder
            folder.getFolders()
                  .forEach(child -> {
                      // Call recursive creation of folderAccess for children without uid
                      I_FolderAccess childFolderAccess = buildNewFolderAccessHierarchy(domainAccess,
                                                                                       child,
                                                                                       timeStamp,
                                                                                       ehrId,
                                                                                       contributionAccess);
                      folderAccess.getSubfoldersList()
                                  .put(UUID.randomUUID(), childFolderAccess);
                  });
        }
        return folderAccess;
    }

    /**
     *
     * @param parentFolder identifier.
     * @param childFolder identifier to define the {@link FolderHierarchyRecord} from.
     * @param inContribution contribution that the {@link  Folder} refers to.
     * @param sysTransaction date of the transaction.
     * @param sysPeriod period of validity of the entity persisted.
     * @return the {@link FolderHierarchyRecord} for persisting the folder identified by the childFolder param.
     */
    private  final FolderHierarchyRecord buildFolderHierarchyRecord(final UUID parentFolder, final UUID childFolder, final UUID inContribution, final Timestamp sysTransaction, final Timestamp sysPeriod){
        FolderHierarchyRecord fhRecord = getContext().newRecord(FolderHierarchy.FOLDER_HIERARCHY);
        fhRecord.setParentFolder(parentFolder);
        fhRecord.setChildFolder(childFolder);
        fhRecord.setInContribution(inContribution);
        fhRecord.setSysTransaction(sysTransaction);
        //fhRecord.setSysPeriod(sysPeriod); sys period can be left to null so the system sets it for the temporal tables.
        return fhRecord;
    }

    /**
     * Returns the last version number of a given folder by counting all
     * previous versions of a given folder id. If there are no previous versions
     * in the history table the version number will be 1. Otherwise the current
     * version equals the count of entries in the folder history table plus 1.
     *
     * @param domainAccess - Database connection access context
     * @param folderId     - UUID of the folder to check for the last version
     * @return Latest version number for the folder
     */
    public static Integer getLastVersionNumber(I_DomainAccess domainAccess, UUID folderId) {

        if (!hasPreviousVersion(domainAccess, folderId)) {
            return 1;
        }
        // Get number of entries as the history table of folders
        int versionCount = domainAccess
                .getContext()
                .fetchCount(FOLDER_HISTORY, FOLDER_HISTORY.ID.eq(folderId));
        // Latest version will be entries plus actual entry count (always 1)
        return versionCount + 1;
    }

    /**
     * Checks if there are existing entries for given folder uuid at the folder
     * history table. If there are entries existing, the folder has been
     * modified during previous actions and there are older versions existing.
     *
     * @param domainAccess - Database connection access context
     * @param folderId     - UUID of folder to check
     * @return Folder has previous versions or not
     */
    public static boolean hasPreviousVersion(I_DomainAccess domainAccess, UUID folderId) {
        return domainAccess
                .getContext()
                .fetchExists(FOLDER_HISTORY, FOLDER_HISTORY.ID.eq(folderId));
    }

    /****Getters and Setters for the FolderRecord to store****/
    public UUID getEhrId() {
        return ehrId;
    }

    public void setEhrId(final UUID ehrId) {
        this.ehrId = ehrId;
    }


    public I_ContributionAccess getContributionAccess() {
        return contributionAccess;
    }

    public void setContributionAccess(final I_ContributionAccess contributionAccess) {
        this.contributionAccess = contributionAccess;
    }

    FolderRecord getFolderRecord() {
        return folderRecord;
    }

    public void setSubfoldersList(final Map<UUID, I_FolderAccess> subfolders) {
        this.subfoldersList=subfolders;

    }

    public  Map<UUID, I_FolderAccess>  getSubfoldersList() {
        return this.subfoldersList;
    }

    public  void setDetails(final ItemStructure details){
        this.details = details;}

    @Override
    public ItemStructure getDetails() {
        return null;
    }

    public  List<ObjectRef> getItems(){
        return this.items;
    }

    public UUID getFolderId(){

        return this.folderRecord.getId();
    }

    public void setFolderId(UUID folderId){

        this.folderRecord.setId(folderId);
    }

    public UUID getInContribution(){
        return this.folderRecord.getInContribution();
    }

    public void setInContribution(UUID inContribution){

        this.folderRecord.setInContribution(inContribution);
    }

    public String getFolderName(){

        return this.folderRecord.getName();
    }

    public void setFolderName(String folderName){

        this.folderRecord.setName(folderName);
    }

    public String getFolderArchetypeNodeId(){

        return this.folderRecord.getArchetypeNodeId();
    }

    public void setFolderNArchetypeNodeId(String folderArchetypeNodeId){

        this.folderRecord.setArchetypeNodeId(folderArchetypeNodeId);
    }

    public boolean isFolderActive(){

        return this.folderRecord.getActive();
    }

    public void setIsFolderActive(boolean folderActive){

        this.folderRecord.setActive(folderActive);
    }

    public ItemStructure getFolderDetails(){

        return this.folderRecord.getDetails();
    }

    public void setFolderDetails(ItemStructure folderDetails){

        this.folderRecord.setDetails(folderDetails);
    }

    public void setFolderSysTransaction(Timestamp folderSysTransaction){

        this.folderRecord.setSysTransaction(folderSysTransaction);
    }

    public Timestamp getFolderSysTransaction(){

        return this.folderRecord.getSysTransaction();
    }

    public Object getFolderSysPeriod(){

        return this.folderRecord.getSysPeriod();
    }

    public void setFolderSysPeriod(Object folderSysPeriod){

        this.folderRecord.setSysPeriod(folderSysPeriod);
    }

    @Override
    public DataAccess getDataAccess() {
        return this;
    }

    @Override
    public int compareTo(final FolderAccessHistory o) {
        return o.getFolderRecord().getId().compareTo(this.folderRecord.getId());
    }

    /**
     * Utility class to parse joow PGObjects that are not automatically managed by jooq.
     */
    private static class PGObjectParser{
        public static Field<Object> parseSysPeriod(Object sysPeriodToParse){
            String sysPeriodVal ="[\"0001-01-01 15:12:15.841936+00\",)";//sample default value with non-sense date.
            if(sysPeriodToParse!=null){
                sysPeriodVal = sysPeriodToParse.toString().replaceAll("::tstzrange", "").replaceAll("'", "");
            }
            return DSL.field(DSL.val(sysPeriodVal) + "::tstzrange");
        }
    }

    private class ObjectRefId extends ObjectId{
        public ObjectRefId(final String value) {
            super(value);
        }
    }


    /***********************GET BY TIME*******************/
    /**
     * @throws IllegalArgumentException when no version in compliance with timestamp is available
     * @throws InternalServerException  on problem with SQL statement or input
     */
    public static int getVersionFromTimeStamp(I_DomainAccess domainAccess, UUID vFolderUid, Timestamp timeCommitted) throws InternalServerException {

        if (timeCommitted == null) {
            return getLastVersionNumber(domainAccess, vFolderUid);
        }
        //get the latest FOLDER time (available in ehr.folder) table
        Record result;
        try {
            result = domainAccess.getContext().select(max(FOLDER.SYS_TRANSACTION).as("mostRecentInTable")).from(FOLDER).where(FOLDER.ID.eq(vFolderUid)).fetchOne();
        } catch (RuntimeException e) {  // generalize SQL exceptions
            throw new InternalServerException("Problem with SQL statement or input", e);
        }
        Timestamp latestCompoTime = (Timestamp) result.get("mostRecentInTable");

        //get the latest version (if more than one) time (available in ehr.FOLDER_history) table
        Record result2;
        try {
            result2 = domainAccess.getContext().select(count().as("countVersionInTable")).from(FOLDER).where(FOLDER_HISTORY.SYS_TRANSACTION.lessOrEqual(timeCommitted).and(FOLDER_HISTORY.ID.eq(vFolderUid))).fetchOne();
        } catch (RuntimeException e) { // generalize SQL exceptions
            throw new InternalServerException("Problem with SQL statement or input", e);
        }
        int versionComHist = (int) result2.get("countVersionInTable");
        if (timeCommitted.compareTo(latestCompoTime) >= 0) {//if the timestamp is after or equal to the sys_transaction of the latest folder available, add one since its version has not been counted for being the one stored in the ehr.folder table
            versionComHist++;
        }
        if (versionComHist == 0) {
            throw new IllegalArgumentException("There are no versions available prior to date " + timeCommitted + " for the the FOLDER with id: " + vFolderUid);
        }
        return versionComHist;
    }


    /**
     * @throws IllegalArgumentException when no version in compliance with timestamp is available or when calculated version number is not greater 0
     * @throws InternalServerException  on problem with SQL statement or input
     * @throws ObjectNotFoundException  when no folder could be found with given input
     */
    public static I_FolderAccess retrieveInstanceByTimestamp(I_DomainAccess domainAccess, UUID folderUid, Timestamp timeCommitted) {

        int version = getVersionFromTimeStamp(domainAccess, folderUid, timeCommitted);

        if (getLastVersionNumber(domainAccess, folderUid) == version) { //current version
            return retrieveInstanceForExistingFolder(domainAccess, folderUid);
        }

        return null;//retrieveCompositionVersion(domainAccess, folderUid, version);
    }


    /**
     * Retrieve instance of {@link I_FolderAccess} with the information needed retrieve the folder and its sub-folders.
     * @param domainAccess providing the information about the DB connection.
     * @param folderId {@link UUID} of the {@link  Folder} to be fetched from the DB.
     * @return the {@link I_FolderAccess} that provides DB access to the {@link  Folder} that corresponds to the provided folderId param.
     * @throws Exception
     */
    public static I_FolderAccess retrieveInstanceForExistingFolder(I_DomainAccess domainAccess, UUID folderId, Timestamp timestamp){

        /***1-retrieve CTE as a table that contains all the rows that allow to infer each parent-child relationship***/
        FolderHierarchy sf =  FOLDER_HIERARCHY.as("sf");

        Table<?> sf_table = table(
                select()
                        .from(FOLDER_HIERARCHY));

        Table<?> folder_table = table(
                select()
                        .from(FOLDER)).as("t_folder1");
        Table<?> folder_table2 = table(
                select()
                        .from(FOLDER)).as("t_folder2");

        Table<?> initial_table = table(
                select()
                        .from(FOLDER_HIERARCHY)
                        .where(
                                FOLDER_HIERARCHY.PARENT_FOLDER.eq(folderId)));

        Field<UUID> subfolderChildFolder = field("subfolders.{0}", FOLDER_HIERARCHY.CHILD_FOLDER.getDataType(), FOLDER_HIERARCHY.CHILD_FOLDER.getUnqualifiedName());
        Field<UUID> subfolderParentFolderRef = field(name("subfolders", "parent_folder"), UUID.class);
        Result<Record> folderSelectedRecordSub = domainAccess.getContext().withRecursive("subfolders").as(
                select().
                        from(initial_table).
                        leftJoin(folder_table).on(initial_table.field("parent_folder", FOLDER_HIERARCHY.PARENT_FOLDER.getType()).eq(
                        folder_table.field("id", FOLDER.ID.getType()))).
                        union(
                                (select().from(sf_table).
                                        innerJoin("subfolders").on(sf_table.field("parent_folder", FOLDER_HIERARCHY.PARENT_FOLDER.getType()).
                                        eq(subfolderChildFolder))).leftJoin(folder_table2).on(
                                        folder_table2.field("id", FOLDER.ID.getType()).eq(subfolderChildFolder)))
        ).select().from(table(name("subfolders"))).fetch();

        /**2-Reconstruct hierarchical structure from DB result**/
        Map<UUID, Map<UUID, I_FolderAccess>> fHierarchyMap = new TreeMap<UUID, Map<UUID, I_FolderAccess>>();
        for(Record record : folderSelectedRecordSub){

            //1-create a folder access for the record if needed
            if(!fHierarchyMap.containsKey((UUID) record.getValue("parent_folder"))){
                fHierarchyMap.put((UUID) record.getValue("parent_folder"), new TreeMap<>());
            }
            fHierarchyMap.get(record.getValue("parent_folder")).put((UUID) record.getValue("child_folder"), buildFolderAccessFromFolderId((UUID)record.getValue("child_folder"), domainAccess, folderSelectedRecordSub));
        }

        /**3-populate result and return**/
        return FolderAccessHistory.buildFolderAccessHierarchy(fHierarchyMap, folderId, null, folderSelectedRecordSub, domainAccess);
    }


    /*select allFolderRows.in_contribution
from (
	select *
	from ehr.folder f
	where f.id='0e5f3019-b527-4173-8f13-d224ac952280'
	union
	select * from ehr.folder_history fh
	where fh.id='0e5f3019-b527-4173-8f13-d224ac952280'
	) allFolderRows
where allFolderRows.sys_transaction = (select max (sys_transaction) from ((
	select *
	from ehr.folder f
	where f.id='0e5f3019-b527-4173-8f13-d224ac952280'
	and f.sys_transaction <= '2019-11-27 16:06:45.303'
	union
	select * from ehr.folder_history fh
	where fh.id='0e5f3019-b527-4173-8f13-d224ac952280'
	and fh.sys_transaction <= '2019-11-27 16:06:45.303'
	)) sss);*/


    private UUID getContributionUidFromTimestamp(UUID folderUid, Timestamp timestamp){
        Table<?> filterByMaxTimeAndFolderId = this.getContext().
                select().from(FOLDER)
                .where(FOLDER.ID.eq(folderUid)
                        .and(FOLDER.SYS_TRANSACTION.lessOrEqual(timestamp)))
                .union(
                        select().from(FOLDER_HISTORY)
                                .where(FOLDER_HISTORY.ID.eq(folderUid).and(FOLDER_HISTORY.SYS_TRANSACTION.lessOrEqual(timestamp))))
                .asTable("contributionTransactionTime");

        Result relativeMaxSysTransaction = this.getContext().
                select(max(filterByMaxTimeAndFolderId.field("sys_transaction"))).
                from(filterByMaxTimeAndFolderId).fetch();

        Table<?> allFolderRows = this.getContext().
                select().
                from(FOLDER).
                where(FOLDER.ID.eq(folderUid)).
                union(select().from(FOLDER_HISTORY).
                        where(FOLDER_HISTORY.ID.eq(folderUid))).asTable("allFolderRows");

        System.out.println("relativeMaxSysTransaction is: " + allFolderRows);

        Result result = this.getContext()
                .select()
                .from(allFolderRows)
                .where(allFolderRows.field("sys_transaction",Timestamp.class).eq((Timestamp) relativeMaxSysTransaction.getValues("max", Timestamp.class).get(0))).fetch();//maybe embed expression for relativeMaxSysTransaction



        System.out.print("fin: "+result.getValues("in_contribution")+"the full record is: "+result);
        return (UUID) result.getValues("in_contribution", UUID.class).get(0);
    }

    /**
     * Retrieves a table with all the information to rebuild the directory related to the folders that have at least one child. If the folderUid provider corresponds to a leave folder then the result will be empty.
     * @param folderUid The top folder UID of the directory or subdirectory that will be retrieved as a table.
     * @param timestamp The timestamp which used to determine which version to retrieve. The method will use the closest version available before or equal to the timestamp provided.
     * @return A table with all the information related to the hierarchy joint to the information of each of the folders that have some child.
     */
    private Result<Record> buildUnionOfFolderHierarchiesTable(UUID folderUid, Timestamp timestamp){

        Table<?> united_hierarchies_table1 = table(
                select()
                        .from(FOLDER_HIERARCHY).where(FOLDER_HIERARCHY.SYS_TRANSACTION.le(timestamp)).
                        union(
                                select().from(FOLDER_HIERARCHY_HISTORY).where(FOLDER_HIERARCHY_HISTORY.SYS_TRANSACTION.le(timestamp)))).asTable("united_hierarchies_table1");

        Table<?> united_hierarchies_table2 = table(
                select()
                        .from(FOLDER_HIERARCHY).where(FOLDER_HIERARCHY.SYS_TRANSACTION.le(timestamp)).
                        union(
                                select().from(FOLDER_HIERARCHY_HISTORY).where(FOLDER_HIERARCHY_HISTORY.SYS_TRANSACTION.le(timestamp)))).asTable("united_hierarchies_table2");

        Table<?> united_hierarchies_tableFileted =
                select()
                        .from(united_hierarchies_table1).where(
                        united_hierarchies_table1.field("sys_transaction", FOLDER_HIERARCHY.SYS_TRANSACTION.getType()).
                                eq(
                                        select(max(united_hierarchies_table2.field("sys_transaction", FOLDER_HIERARCHY.SYS_TRANSACTION.getType()))).
                                                from(united_hierarchies_table2).
                                                where(
                                                        (united_hierarchies_table1.field("parent_folder", FOLDER_HIERARCHY.PARENT_FOLDER.getType())).eq(
                                                                united_hierarchies_table2.field("parent_folder", FOLDER_HIERARCHY.PARENT_FOLDER.getType())

                                                        ).and(
                                                                united_hierarchies_table1.field("child_folder", FOLDER_HIERARCHY.CHILD_FOLDER.getType()).eq(
                                                                        united_hierarchies_table2.field("child_folder", FOLDER_HIERARCHY.CHILD_FOLDER.getType())
                                                                )

                                                        )))
                ).asTable("united_hierarchies_tableFileted");


        /*filter by the provided timestamp only the ones equal or older*/
        Table<?>  fhf_timestamp1 = select().from(united_hierarchies_tableFileted).asTable();
        Table<?>  fhf_timestamp2 = select().from(united_hierarchies_tableFileted).asTable();


        /*Retrieve for each partent folder the the latest transaction time so as to ignore previous versions*/
        Table<?>  fhf_timestamp_version2 = select(fhf_timestamp1.field("parent_folder", FOLDER.ID.getType()).as("parent_folder_id"),  max(fhf_timestamp2.field("sys_transaction", FOLDER.SYS_TRANSACTION.getType())).as("latest_sys_transaction")).
                from(fhf_timestamp1).
                groupBy(
                        fhf_timestamp1.field("parent_folder", FOLDER.ID.getType()))
                .asTable();

        /*make the unified table with only the rows that correspond to the latest transactions*/
/*        Table<?>  filteredHierarchicalTable = select().
                from(united_hierarchies_tableFileted, fhf_timestamp_version2).
                where(
                        united_hierarchies_tableFileted.field("parent_folder", FOLDER.ID.getType()).
                                eq(fhf_timestamp_version2.field("parent_folder_id", FOLDER.ID.getType())).
                                and(united_hierarchies_tableFileted.field("sys_transaction", FOLDER.SYS_TRANSACTION.getType()).
                                        eq(fhf_timestamp_version2.field("latest_sys_transaction", FOLDER.SYS_TRANSACTION.getType()))))
                .asTable();*/
        Table<?>  filteredHierarchicalTable = select().
                from(united_hierarchies_tableFileted, fhf_timestamp_version2).
                where(
                        united_hierarchies_tableFileted.field("parent_folder", FOLDER.ID.getType()).
                                eq(fhf_timestamp_version2.field("parent_folder_id", FOLDER.ID.getType())).
                                and(united_hierarchies_tableFileted.field("sys_transaction", FOLDER.SYS_TRANSACTION.getType()).
                                        eq(fhf_timestamp_version2.field("latest_sys_transaction", FOLDER.SYS_TRANSACTION.getType()))))
                .asTable();
        System.out.println("ALL FOLDER ROWS filteredHierarchicalTable .....\n"+this.getContext().select().from(filteredHierarchicalTable).fetch());

        Field<UUID> subfolderParentFolderRef = field(name("subfolders", "parent_folder"), UUID.class);

        Table<?> allFolderRowsFolderTable = this.getContext().
                select(FOLDER.ID, FOLDER.IN_CONTRIBUTION, FOLDER.NAME, FOLDER.ARCHETYPE_NODE_ID, FOLDER.ACTIVE, FOLDER.DETAILS, FOLDER.SYS_TRANSACTION, FOLDER.SYS_PERIOD).
                from(FOLDER, filteredHierarchicalTable).where(FOLDER.ID.eq(filteredHierarchicalTable.field("parent_folder", UUID.class)).and(FOLDER.IN_CONTRIBUTION.eq(filteredHierarchicalTable.field("in_contribution", UUID.class)))).asTable();

        Table<?> allFolderRowsFolderHistoryTable = this.getContext().
                select(FOLDER_HISTORY.ID, FOLDER_HISTORY.IN_CONTRIBUTION, FOLDER_HISTORY.NAME, FOLDER_HISTORY.ARCHETYPE_NODE_ID, FOLDER_HISTORY.ACTIVE, FOLDER_HISTORY.DETAILS, FOLDER_HISTORY.SYS_TRANSACTION, FOLDER_HISTORY.SYS_PERIOD).
                from(FOLDER_HISTORY, filteredHierarchicalTable).
                where(FOLDER_HISTORY.ID.
                        eq(filteredHierarchicalTable.field("parent_folder", UUID.class)).
                        and(FOLDER_HISTORY.IN_CONTRIBUTION.
                                eq(filteredHierarchicalTable.field("in_contribution", UUID.class)))).asTable();

        System.out.println("ALL FOLDER ROWS allFolderRowsFolderHistoryTable .....\n"+this.getContext().select().from(allFolderRowsFolderHistoryTable).fetch());

        Table<?> allFolderRowsUnifiedAndFilteredInitial = this.getContext().
                select(allFolderRowsFolderTable.field("id", UUID.class), allFolderRowsFolderTable.field("in_contribution", UUID.class).as("in_contribution_folder_info"), allFolderRowsFolderTable.field("name", FOLDER.NAME.getType()),allFolderRowsFolderTable.field("archetype_node_id", FOLDER.ARCHETYPE_NODE_ID.getType()), allFolderRowsFolderTable.field("active",FOLDER.ACTIVE.getType()), allFolderRowsFolderTable.field("details", FOLDER.DETAILS.getType()), allFolderRowsFolderTable.field("sys_transaction", FOLDER.SYS_TRANSACTION.getType()).as("sys_transaction_folder"), allFolderRowsFolderTable.field("sys_period", FOLDER.SYS_PERIOD.getType()).as("sys_period_folder")).
                from(allFolderRowsFolderTable).
                union(
                        select(allFolderRowsFolderHistoryTable.field("id", UUID.class), allFolderRowsFolderHistoryTable.field("in_contribution", UUID.class).as("in_contribution_folder_info"), allFolderRowsFolderHistoryTable.field("name", FOLDER.NAME.getType()),allFolderRowsFolderHistoryTable.field("archetype_node_id", FOLDER.ARCHETYPE_NODE_ID.getType()), allFolderRowsFolderHistoryTable.field("active",FOLDER.ACTIVE.getType()), allFolderRowsFolderHistoryTable.field("details", FOLDER.DETAILS.getType()), allFolderRowsFolderHistoryTable.field("sys_transaction", FOLDER.SYS_TRANSACTION.getType()).as("sys_transaction_folder"), allFolderRowsFolderHistoryTable.field("sys_period", FOLDER.SYS_PERIOD.getType()).as("sys_period_folder")).
                                from(allFolderRowsFolderHistoryTable)).asTable();

        Table<?> allFolderRowsUnifiedAndFilteredIterative = this.getContext().
                select(allFolderRowsFolderTable.field("id", UUID.class), allFolderRowsFolderTable.field("in_contribution", UUID.class).as("in_contribution_folder_info"), allFolderRowsFolderTable.field("name", FOLDER.NAME.getType()),allFolderRowsFolderTable.field("archetype_node_id", FOLDER.ARCHETYPE_NODE_ID.getType()), allFolderRowsFolderTable.field("active",FOLDER.ACTIVE.getType()), allFolderRowsFolderTable.field("details", FOLDER.DETAILS.getType()), allFolderRowsFolderTable.field("sys_transaction", FOLDER.SYS_TRANSACTION.getType()).as("sys_transaction_folder"), allFolderRowsFolderTable.field("sys_period", FOLDER.SYS_PERIOD.getType()).as("sys_period_folder")).
                from(allFolderRowsFolderTable).
                union(
                        select(allFolderRowsFolderHistoryTable.field("id", UUID.class), allFolderRowsFolderHistoryTable.field("in_contribution", UUID.class).as("in_contribution_folder_info"), allFolderRowsFolderHistoryTable.field("name", FOLDER.NAME.getType()),allFolderRowsFolderHistoryTable.field("archetype_node_id", FOLDER.ARCHETYPE_NODE_ID.getType()), allFolderRowsFolderHistoryTable.field("active",FOLDER.ACTIVE.getType()), allFolderRowsFolderHistoryTable.field("details", FOLDER.DETAILS.getType()), allFolderRowsFolderHistoryTable.field("sys_transaction", FOLDER.SYS_TRANSACTION.getType()).as("sys_transaction_folder"), allFolderRowsFolderHistoryTable.field("sys_period", FOLDER.SYS_PERIOD.getType()).as("sys_period_folder")).
                                from(allFolderRowsFolderHistoryTable)).asTable();

        System.out.println("ALL FOLDER ROWS iterative .....\n"+this.getContext().select().from(allFolderRowsUnifiedAndFilteredIterative).fetch());


        Field<UUID> subfolderChildFolder = field("subfolders.{0}", FOLDER_HIERARCHY.CHILD_FOLDER.getDataType(), FOLDER_HIERARCHY.CHILD_FOLDER.getUnqualifiedName());
        Field<Timestamp> subfolderSysTran = field("\"subfolders\".\"sys_transaction\"", FOLDER_HIERARCHY.SYS_TRANSACTION.getDataType(), FOLDER_HIERARCHY.SYS_TRANSACTION.getUnqualifiedName());

        Table<?> initial_table2 =
                (select()
                        .from(filteredHierarchicalTable).
                                leftJoin(allFolderRowsUnifiedAndFilteredInitial).
                                on(
                                        filteredHierarchicalTable.field("parent_folder", UUID.class)
                                                .eq(allFolderRowsUnifiedAndFilteredInitial.field("id", UUID.class)))
                        .where(
                                filteredHierarchicalTable.field("parent_folder", UUID.class).eq(folderUid))).asTable();


        Result<Record> folderSelectedRecordSub = this.getContext().withRecursive("subfolders").as(
                select().
                        from(initial_table2).
                        union(
                                (select().from(filteredHierarchicalTable).
                                        innerJoin("subfolders").
                                        on(
                                                filteredHierarchicalTable.field("parent_folder", FOLDER_HIERARCHY.PARENT_FOLDER.getType()).
                                                        eq(subfolderChildFolder)
                                        )).
                                        leftJoin(allFolderRowsUnifiedAndFilteredIterative).
                                        on(
                                                allFolderRowsUnifiedAndFilteredIterative.field("id", FOLDER.ID.getType()).eq(subfolderChildFolder)))
        ).select().from(table(name("subfolders"))).fetch();

        System.out.println("end method ....."+folderSelectedRecordSub);

        return folderSelectedRecordSub;
    }

    public UUID calculateContributionUidFromTimestamp(UUID folderUid, Timestamp timestamp){



        Table<?> filterByMaxTimeAndFolderId = this.getContext().
                select().from(FOLDER)
                .where(FOLDER.ID.eq(folderUid)
                        .and(FOLDER.SYS_TRANSACTION.lessOrEqual(timestamp)))
                .union(
                        select().from(FOLDER_HISTORY)
                                .where(FOLDER_HISTORY.ID.eq(folderUid).and(FOLDER_HISTORY.SYS_TRANSACTION.lessOrEqual(timestamp))))
                .asTable("contributionTransactionTime");

        Result relativeMaxSysTransaction = this.getContext().
                select(max(filterByMaxTimeAndFolderId.field("sys_transaction"))).
                from(filterByMaxTimeAndFolderId).fetch();

        Table<?> allFolderRows = this.getContext().
                select().
                from(FOLDER).
                where(FOLDER.ID.eq(folderUid)).
                union(select().from(FOLDER_HISTORY).
                        where(FOLDER_HISTORY.ID.eq(folderUid))).asTable("allFolderRows");

        System.out.println("relativeMaxSysTransaction is: " + allFolderRows);

        Result result = this.getContext()
                .select()
                .from(allFolderRows)
                .where(allFolderRows.field("sys_transaction",Timestamp.class).eq(/*Timestamp.valueOf("2019-11-27 16:05:33.54")*/ (Timestamp) relativeMaxSysTransaction.getValues("max", Timestamp.class).get(0))).fetch();//maybe embed expression for relativeMaxSysTransaction



        System.out.print("fin: "+result.getValues("in_contribution")+"the full record is: "+result);
        return (UUID) result.getValues("in_contribution", UUID.class).get(0);

    }

}