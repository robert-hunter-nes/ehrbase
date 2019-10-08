/*
 * Modifications copyright (C) 2019 Christian Chevalley, Vitasystems GmbH and Hannover Medical School,
 * Jake Smolka (Hannover Medical School), and Luis Marco-Ruiz (Hannover Medical School).

 * This file is part of Project EHRbase

 * Copyright (c) 2015 Christian Chevalley
 * This file is part of Project Ethercis
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

import com.nedap.archie.rm.archetyped.Archetyped;
import com.nedap.archie.rm.archetyped.Locatable;
import com.nedap.archie.rm.datastructures.ItemStructure;
import com.nedap.archie.rm.ehr.EhrStatus;
import com.nedap.archie.rm.generic.PartySelf;
import com.nedap.archie.rm.support.identification.HierObjectId;
import com.nedap.archie.rm.support.identification.ObjectId;
import com.nedap.archie.rm.support.identification.PartyRef;
import org.apache.commons.collections4.map.MultiValueMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ehrbase.api.exception.InternalServerException;
import org.ehrbase.dao.access.interfaces.*;
import org.ehrbase.dao.access.support.DataAccess;
import org.ehrbase.dao.access.util.ContributionDef;
import org.ehrbase.jooq.pg.enums.ContributionDataType;
import org.ehrbase.jooq.pg.tables.records.ContributionRecord;
import org.ehrbase.jooq.pg.tables.records.EhrRecord;
import org.ehrbase.jooq.pg.tables.records.IdentifierRecord;
import org.ehrbase.jooq.pg.tables.records.StatusRecord;
import org.ehrbase.serialisation.RawJson;
import org.ehrbase.service.BaseService;
import org.jooq.DSLContext;
import org.jooq.InsertQuery;
import org.jooq.Record;
import org.jooq.UpdateQuery;
import org.jooq.impl.DSL;
import org.postgresql.util.PGobject;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.ehrbase.jooq.pg.Tables.*;

/**
 * Created by Christian Chevalley on 4/17/2015.
 */
public class EhrAccess extends DataAccess implements I_EhrAccess {

    private static final Logger log = LogManager.getLogger(EhrAccess.class);
    public static final String JSONB = "::jsonb";
    public static final String EXCEPTION = " exception:";
    public static final String COULD_NOT_RETRIEVE_EHR_FOR_ID = "Could not retrieve EHR for id:";
    public static final String COULD_NOT_RETRIEVE_EHR_FOR_PARTY = "Could not retrieve EHR for party:";
    private EhrRecord ehrRecord;
    private StatusRecord statusRecord = null;
    private boolean isNew = false;

    //holds the non serialized archetyped other_details structure
    private Locatable otherDetails = null;
    private String otherDetailsSerialized = null; //kind of a hack really, used to deal with RAW JSON without a template!
    private String otherDetailsTemplateId;

    private I_ContributionAccess contributionAccess = null; //locally referenced contribution associated to ehr transactions

    //set this variable to change the identification  mode in status
    private PARTY_MODE party_identifier = PARTY_MODE.EXTERNAL_REF;

    /**
     * @throws InternalServerException if creating or retrieving system failed
     */
    public EhrAccess(DSLContext context, UUID partyId, UUID systemId, UUID directoryId, UUID accessId, UUID ehrId) {
        super(context, null, null);

        this.ehrRecord = context.newRecord(EHR_);
        if (ehrId != null) {    // checking for and executing case of custom ehr ID
            ehrRecord.setId(ehrId);
        } else {
            ehrRecord.setId(UUID.randomUUID());
        }

        //retrieveInstanceByNamedSubject an existing status for this party (which should not occur
        if (!context.fetch(STATUS, STATUS.PARTY.eq(partyId)).isEmpty()) {
            log.warn("This party is already associated to an EHR");
            throw new IllegalArgumentException("Party:" + partyId + " already associated to an EHR, please retrieveInstanceByNamedSubject the associated EHR for updates instead");
        }

        //storeComposition a new status
        statusRecord = context.newRecord(STATUS);
        statusRecord.setId(UUID.randomUUID());
        statusRecord.setIsModifiable(true);
        statusRecord.setIsQueryable(true);
        statusRecord.setParty(partyId);
        statusRecord.setEhrId(ehrRecord.getId());

        ehrRecord.setSystemId(systemId);
        ehrRecord.setDirectory(directoryId);
        ehrRecord.setAccess(accessId);

        if (ehrRecord.getSystemId() == null) { //storeComposition a default entry for the current system
            ehrRecord.setSystemId(I_SystemAccess.createOrRetrieveLocalSystem(this));
        }


        this.isNew = true;

        //associate a contribution with this EHR
        contributionAccess = I_ContributionAccess.getInstance(this, ehrRecord.getId());
        contributionAccess.setState(ContributionDef.ContributionState.COMPLETE);

    }

    /**
     * Internal constructor to create minimal instance to customize further before returning.
     *
     * @param domainAccess DB domain access object
     */
    private EhrAccess(I_DomainAccess domainAccess) {
        super(domainAccess);
        //associate a contribution with this EHR
        contributionAccess = I_ContributionAccess.getInstance(this, null);
        contributionAccess.setState(ContributionDef.ContributionState.COMPLETE);
    }

    /**
     * @throws IllegalArgumentException if retrieving failed for given input
     */
    public static UUID retrieveInstanceBySubject(I_DomainAccess domainAccess, UUID subjectUuid) {
        Record record;
        DSLContext context = domainAccess.getContext();

        try {
            record = context.select(STATUS.EHR_ID).from(STATUS)
                    .where(STATUS.PARTY.eq
                            (context.select(PARTY_IDENTIFIED.ID)
                                    .from(PARTY_IDENTIFIED)
                                    .where(PARTY_IDENTIFIED.ID.eq(subjectUuid))
                            )
                    ).fetchOne();

        } catch (Exception e) { //possibly not unique for a party: this is not permitted!
            log.warn(COULD_NOT_RETRIEVE_EHR_FOR_PARTY + subjectUuid + EXCEPTION + e);
            throw new IllegalArgumentException("Could not retrieve  EHR for party:" + subjectUuid + EXCEPTION + e);
        }

        if (record == null || record.size() == 0) {
            log.warn(COULD_NOT_RETRIEVE_EHR_FOR_PARTY + subjectUuid);
            return null;
        }

        return (UUID) record.getValue(0);
    }

    /**
     * @throws IllegalArgumentException if retrieving failed for given input
     */
    public static UUID retrieveInstanceBySubject(I_DomainAccess domainAccess, String subjectId, String issuerSpace) {
        Record record;
        DSLContext context = domainAccess.getContext();

        //get the corresponding party Id from the codification space provided by an issuer
        IdentifierRecord identifierRecord = context.fetchOne(IDENTIFIER, IDENTIFIER.ID_VALUE.eq(subjectId).and(IDENTIFIER.ISSUER.eq(issuerSpace)));

        if (identifierRecord == null)
            throw new IllegalArgumentException("Could not invalidateContent an identified party for code:" + subjectId + " issued by:" + issuerSpace);

        try {
            record = context.select(STATUS.EHR_ID).from(STATUS)
                    .where(STATUS.PARTY.eq
                            (context.select(PARTY_IDENTIFIED.ID)
                                    .from(PARTY_IDENTIFIED)
                                    .where(PARTY_IDENTIFIED.ID.eq(identifierRecord.getParty()))
                            )
                    ).fetchOne();

        } catch (Exception e) { //possibly not unique for a party: this is not permitted!
            log.warn(COULD_NOT_RETRIEVE_EHR_FOR_PARTY + subjectId + EXCEPTION + e);
            throw new IllegalArgumentException(COULD_NOT_RETRIEVE_EHR_FOR_PARTY + subjectId + EXCEPTION + e);
        }

        if (record == null || record.size() == 0) {
            log.warn(COULD_NOT_RETRIEVE_EHR_FOR_PARTY + subjectId);
            return null;
        }

        return (UUID) record.getValue(0);
    }

    /**
     * @throws IllegalArgumentException if retrieving failed for given input
     */
    public static UUID retrieveInstanceBySubjectExternalRef(I_DomainAccess domainAccess, String subjectId, String issuerSpace) {
        Record record;
        DSLContext context = domainAccess.getContext();

        try {
            record = context.select(STATUS.EHR_ID).from(STATUS)
                    .where(STATUS.PARTY.eq
                            (context.select(PARTY_IDENTIFIED.ID)
                                    .from(PARTY_IDENTIFIED)
                                    .where(PARTY_IDENTIFIED.PARTY_REF_VALUE.eq(subjectId)
                                            .and(PARTY_IDENTIFIED.PARTY_REF_NAMESPACE.eq(issuerSpace)))
                            )
                    ).fetchOne();

        } catch (Exception e) { //possibly not unique for a party: this is not permitted!
            log.warn("Could not ehr for party:" + subjectId + EXCEPTION + e);
            throw new IllegalArgumentException(COULD_NOT_RETRIEVE_EHR_FOR_PARTY + subjectId + EXCEPTION + e);
        }

        if (record == null || record.size() == 0) {
            log.warn("Could not retrieve ehr for party:" + subjectId);
            return null;
        }

        return (UUID) record.getValue(0);
    }

    /**
     * @throws IllegalArgumentException if retrieving failed for given input
     */
    public static I_EhrAccess retrieveInstanceByStatus(I_DomainAccess domainAccess, UUID status) {
        EhrAccess ehrAccess = new EhrAccess(domainAccess);

        Record record;

        ehrAccess.statusRecord = domainAccess.getContext().fetchOne(STATUS, STATUS.ID.eq(status));

        try {
            record = domainAccess.getContext().selectFrom(EHR_)
                    .where(EHR_.ID.eq(ehrAccess.statusRecord.getEhrId()))
                    .fetchOne();
        } catch (Exception e) { //possibly not unique for a party: this is not permitted!
            log.warn("Could not retrieveInstanceByNamedSubject ehr for status:" + status + EXCEPTION + e);
            throw new IllegalArgumentException("Could not retrieveInstanceByNamedSubject EHR for status:" + status + EXCEPTION + e);
        }

        if (record.size() == 0) {
            log.warn("Could not retrieveInstanceByNamedSubject ehr for status:" + status);
            return null;
        }

        ehrAccess.ehrRecord = (EhrRecord) record;

        ehrAccess.isNew = false;

        return ehrAccess;
    }

    /**
     * @throws IllegalArgumentException when either no EHR for ID, or problem with data structure of EHR, or DB inconsistency
     */
    public static I_EhrAccess retrieveInstance(I_DomainAccess domainAccess, UUID ehrId) {
        DSLContext context = domainAccess.getContext();
        EhrAccess ehrAccess = new EhrAccess(domainAccess);

        Record record;

        try {
            record = context.selectFrom(EHR_)
                    .where(EHR_.ID.eq(ehrId))
                    .fetchOne();
        } catch (Exception e) { //possibly not unique for a party: this is not permitted!
            log.warn(COULD_NOT_RETRIEVE_EHR_FOR_ID + ehrId + EXCEPTION + e);
            throw new IllegalArgumentException(COULD_NOT_RETRIEVE_EHR_FOR_ID + ehrId + EXCEPTION + e);
        }

        if (record == null || record.size() == 0) {
            log.warn(COULD_NOT_RETRIEVE_EHR_FOR_ID + ehrId);
            return null;
        }

        ehrAccess.ehrRecord = (EhrRecord) record;
        //retrieveInstanceByNamedSubject the corresponding status
        ehrAccess.statusRecord = context.fetchOne(STATUS, STATUS.EHR_ID.eq(ehrAccess.ehrRecord.getId()));

        //rebuild otherDetails
        if (ehrAccess.statusRecord.getOtherDetails() != null) {
            String serialized = ((PGobject) ehrAccess.statusRecord.getOtherDetails()).getValue();
            ehrAccess.otherDetails = new RawJson().unmarshal(serialized, ItemStructure.class);
        }

        ehrAccess.isNew = false;

        //retrieve the current contribution for this ehr
        ContributionRecord contributionRecord = context.fetchOne(CONTRIBUTION, CONTRIBUTION.EHR_ID.eq(ehrAccess.ehrRecord.getId()).and(CONTRIBUTION.CONTRIBUTION_TYPE.eq(ContributionDataType.ehr)));
        if (contributionRecord == null)
            throw new IllegalArgumentException("DB inconsistency: could not find a related contribution for ehr=" + ehrAccess.ehrRecord.getId());

        UUID contributionId = contributionRecord.getId();

        if (contributionId != null) {
            ehrAccess.setContributionAccess(I_ContributionAccess.retrieveInstance(domainAccess, contributionId));
        }

        return ehrAccess;
    }

    /**
     * @throws IllegalArgumentException when no EHR found for ID
     */
    public static Map<String, Object> fetchSubjectIdentifiers(I_DomainAccess domainAccess, UUID ehrId) {
        EhrAccess ehrAccess = (EhrAccess) retrieveInstance(domainAccess, ehrId);
        DSLContext context = domainAccess.getContext();

        if (ehrAccess == null)
            throw new IllegalArgumentException("No ehr found for id:" + ehrId);

        Map<String, Object> idlist = new MultiValueMap<>();

        //getNewFolderAccessInstance the corresponding subject Identifiers

        context.selectFrom(IDENTIFIER).
                where(IDENTIFIER.PARTY.eq(getParty(ehrAccess))).fetch()
                .forEach(record -> {
                    idlist.put("identifier_issuer", record.getIssuer());
                    idlist.put("identifier_id_value", record.getIdValue());
                });

        //get the list of ref attributes
        context.selectFrom(PARTY_IDENTIFIED)
                .where(PARTY_IDENTIFIED.ID.eq(getParty(ehrAccess)))
                .fetch()
                .forEach(record -> {
                    idlist.put("ref_name_space", record.getPartyRefNamespace());
                    idlist.put("id_value", record.getPartyRefValue());
                    idlist.put("ref_name_scheme", record.getPartyRefScheme());
                    idlist.put("ref_party_type", record.getPartyRefType());
                });

        return idlist;
    }

    /**
     * FIXME: check this method. appears to be needed later on. problematic: it actually gets a list of entries, not compositions. why only with three attributes? what about the unique key problem below?
     *
     * @throws IllegalArgumentException when no EHR found for ID
     */
    public static Map<String, Map<String, String>> getCompositionList(I_DomainAccess domainAccess, UUID ehrId) {
        EhrAccess ehrAccess = (EhrAccess) retrieveInstance(domainAccess, ehrId);
        DSLContext context = domainAccess.getContext();

        if (ehrAccess == null)
            throw new IllegalArgumentException("No ehr found for id:" + ehrId);

        Map<String, Map<String, String>> compositionlist = new HashMap<>(); // unique keys

        context.selectFrom(ENTRY).where(
                ENTRY.COMPOSITION_ID.eq(
                        context.select(COMPOSITION.ID).from(COMPOSITION).where(COMPOSITION.EHR_ID.eq(ehrId)))
        ).fetch().forEach(record -> {
            Map<String, String> details = new HashMap<>();
            details.put("composition_id", record.getCompositionId().toString());
            details.put("templateId", record.getTemplateId());
            details.put("date", record.getSysTransaction().toString());
            compositionlist.put("details", details);    // FIXME: bug? gets overwritten if more than 1 put() with this static key

        });

        return compositionlist;
    }

    private static UUID getParty(EhrAccess ehrAccess) {
        return ehrAccess.getStatusRecord().getParty();
    }

    // TODO build this for wrong requirement - so delete if really not needed at end of /ehr endpoints' implementation
    public static boolean hasPreviousVersionOfStatus(I_DomainAccess domainAccess, UUID ehrStatusId) {
        return domainAccess.getContext().fetchExists(STATUS_HISTORY, STATUS_HISTORY.ID.eq(ehrStatusId));
    }

    @Override
    public DataAccess getDataAccess() {
        return this;
    }

    private String serializeOtherDetails() {

        return new RawJson().marshal(otherDetails);

    }

    @Override
    public void setAccess(UUID access) {
        ehrRecord.setAccess(access);
    }

    @Override
    public void setDirectory(UUID directory) {
        ehrRecord.setDirectory(directory);
    }

    @Override
    public void setSystem(UUID system) {
        ehrRecord.setSystemId(system);
    }

    @Override
    public void setModifiable(Boolean modifiable) {
        statusRecord.setIsModifiable(modifiable);
    }

    @Override
    public void setQueryable(Boolean queryable) {
        statusRecord.setIsQueryable(queryable);
    }

    /**
     * @throws IllegalArgumentException when EHR couldn't be stored
     */
    @Override
    public UUID commit(Timestamp transactionTime) {

        ehrRecord.setDateCreated(transactionTime);
//        ehrRecord.setDateCreatedTzid(transactionTime.toLocalDateTime().getZone().getID());
        ehrRecord.store();

        if (isNew && statusRecord != null) {
            UUID uuid = UUID.randomUUID();
            InsertQuery<?> insertQuery = context.insertQuery(STATUS);
            insertQuery.addValue(STATUS.ID, uuid);
            insertQuery.addValue(STATUS.EHR_ID, ehrRecord.getId());
            insertQuery.addValue(STATUS.IS_QUERYABLE, statusRecord.getIsQueryable());
            insertQuery.addValue(STATUS.IS_MODIFIABLE, statusRecord.getIsModifiable());
            insertQuery.addValue(STATUS.PARTY, statusRecord.getParty());
//            Field otherDetailsField = DSL.field(STATUS.OTHER_DETAILS+"::jsonb");
            if (otherDetails != null) {
                insertQuery.addValue(STATUS.OTHER_DETAILS, (Object) DSL.field(DSL.val(serializeOtherDetails()) + JSONB));
            } else if (otherDetailsSerialized != null)
                insertQuery.addValue(STATUS.OTHER_DETAILS, (Object) DSL.field(DSL.val(otherDetailsSerialized) + JSONB));

            insertQuery.addValue(STATUS.SYS_TRANSACTION, transactionTime);

            Integer result = insertQuery.execute();

            if (result == 0)
                throw new IllegalArgumentException("Could not store Ehr Status");
//            statusRecord.store();
        }

        return ehrRecord.getId();
    }

    /**
     * @throws InternalServerException because inherited interface function isn't implemented in this class
     * @deprecated
     */
    @Deprecated
    @Override
    public UUID commit() {
        throw new InternalServerException("INTERNAL: this commit is not legal");
    }

    /**
     * @throws IllegalArgumentException when EHR couldn't be stored
     */
    @Override
    public UUID commit(UUID committerId, UUID systemId, String description) {
        Timestamp timestamp = Timestamp.valueOf(LocalDateTime.now());
        //associate a contribution (with contribution's audit embedded) with this ehr.
        UUID uuid = commit(timestamp);
        contributionAccess = I_ContributionAccess.getInstance(this, ehrRecord.getId());
        contributionAccess.commit(timestamp, committerId, systemId, ContributionDataType.ehr, ContributionDef.ContributionState.COMPLETE, I_ConceptAccess.ContributionChangeType.CREATION, description);
        return uuid;
    }

    @Override
    public Boolean update(Timestamp transactionTime) {
        return update(transactionTime, false);
    }

    @Override
    public Boolean update(Timestamp transactionTime, boolean force) {
        boolean result = false;

        if (force || statusRecord.changed()) {

            UpdateQuery<?> updateQuery = context.updateQuery(STATUS);
            updateQuery.addValue(STATUS.EHR_ID, ehrRecord.getId());
            updateQuery.addValue(STATUS.IS_QUERYABLE, statusRecord.getIsQueryable());
            updateQuery.addValue(STATUS.IS_MODIFIABLE, statusRecord.getIsModifiable());
            updateQuery.addValue(STATUS.PARTY, statusRecord.getParty());

            if (otherDetails != null) {
                updateQuery.addValue(STATUS.OTHER_DETAILS, (Object) DSL.field(DSL.val(serializeOtherDetails()) + JSONB));
            } else if (otherDetailsSerialized != null)
                updateQuery.addValue(STATUS.OTHER_DETAILS, (Object) DSL.field(DSL.val(otherDetailsSerialized) + JSONB));

            updateQuery.addValue(STATUS.SYS_TRANSACTION, transactionTime);
            updateQuery.addConditions(STATUS.ID.eq(statusRecord.getId()));

            result |= updateQuery.execute() > 0;
        }

        if (force || ehrRecord.changed()) {
            ZonedDateTime committedTime = ZonedDateTime.now();
            ehrRecord.setDateCreated(Timestamp.valueOf(committedTime.toLocalDateTime()));
            ehrRecord.setDateCreatedTzid(committedTime.getZone().getId());
            result |= ehrRecord.update() > 0;

        }

        return result;
    }

    /**
     * @throws InternalServerException because inherited interface function isn't implemented in this class
     * @deprecated
     */
    @Deprecated
    @Override
    public Boolean update() {
        throw new InternalServerException("INTERNAL: this update is not legal");
    }

    /**
     * @throws InternalServerException because inherited interface function isn't implemented in this class
     * @deprecated
     */
    @Deprecated
    @Override
    public Boolean update(Boolean force) {
        throw new InternalServerException("INTERNAL: this update is not legal");
        //return update(Timestamp.valueOf(LocalDateTime.now()), force);
    }

    @Override
    public Boolean update(UUID committerId, UUID systemId, ContributionDef.ContributionState state, I_ConceptAccess.ContributionChangeType contributionChangeType, String description) {
        Timestamp timestamp = Timestamp.valueOf(LocalDateTime.now());
        contributionAccess.update(timestamp, committerId, systemId, null, state, contributionChangeType, description);
        return update(timestamp);
    }

    /**
     * @throws InternalServerException because inherited interface function isn't implemented in this class
     * @deprecated
     */
    @Deprecated
    @Override
    public Integer delete() {
        throw new InternalServerException("INTERNAL: this delete is not legal");
    }

    public I_EhrAccess retrieveByStatus(UUID status) {
        return retrieveInstanceByStatus(this, status);
    }

    /**
     * @throws IllegalArgumentException when instance's EHR ID can't be matched to existing one
     */
    @Override
    public UUID reload() {
        Record record;

        try {
            record = context.selectFrom(EHR_)
                    .where(EHR_.ID.eq(getId()))
                    .fetchOne();
        } catch (Exception e) { //possibly not unique for a party: this is not permitted!
            log.warn(COULD_NOT_RETRIEVE_EHR_FOR_ID + getId() + EXCEPTION + e);
            throw new IllegalArgumentException(COULD_NOT_RETRIEVE_EHR_FOR_ID + getId() + EXCEPTION + e);
        }

        if (record == null || record.size() == 0) {
            log.warn(COULD_NOT_RETRIEVE_EHR_FOR_ID + getId());
            return null;
        }

        ehrRecord = (EhrRecord) record;
        //retrieveInstanceByNamedSubject the corresponding status
        statusRecord = context.fetchOne(STATUS, STATUS.EHR_ID.eq(ehrRecord.getId()));
        isNew = false;

        return getId();
    }

    public I_EhrAccess retrieve(UUID id) {
        return retrieveInstance(this, id);
    }

    public EhrRecord getEhrRecord() {
        return ehrRecord;
    }

    private StatusRecord getStatusRecord() {
        return statusRecord;
    }

    public boolean isNew() {
        return isNew;
    }

    @Override
    public UUID getParty() {
        return statusRecord.getParty();
    }

    @Override
    public void setParty(UUID partyId) {
        statusRecord.setParty(partyId);
    }

    @Override
    public UUID getId() {
        return ehrRecord.getId();
    }

    @Override
    public Boolean isModifiable() {
        return statusRecord.getIsModifiable();
    }

    @Override
    public Boolean isQueryable() {
        return statusRecord.getIsQueryable();
    }

    @Override
    public UUID getSystemId() {
        return ehrRecord.getSystemId();
    }

    @Override
    public UUID getStatusId() {
        return context.fetchOne(STATUS, STATUS.EHR_ID.eq(ehrRecord.getId())).getId();
    }

    @Override
    public UUID getDirectoryId() {
        return ehrRecord.getDirectory();
    }

    @Override
    public UUID getAccessId() {
        return ehrRecord.getAccess();
    }

    @Override
    public void setOtherDetails(Locatable otherDetails, String templateId) {
        this.otherDetails = otherDetails;
        this.otherDetailsTemplateId = Optional.ofNullable(otherDetails).map(Locatable::getArchetypeDetails).map(Archetyped::getTemplateId).map(ObjectId::getValue).orElse(null);
    }


    @Override
    public Locatable getOtherDetails() {
        return otherDetails;
    }


    public I_ContributionAccess getContributionAccess() {
        return contributionAccess;
    }

    @Override
    public void setContributionAccess(I_ContributionAccess contributionAccess) {
        this.contributionAccess = contributionAccess;
    }


    // TODO build this for wrong requirement - so delete if really not needed at end of /ehr endpoints' implementation
    @Override
    public Integer getLastVersionNumberOfStatus(I_DomainAccess domainAccess, UUID ehrStatusId) {

        if (!hasPreviousVersionOfStatus(domainAccess, ehrStatusId))
            return 1;

        int versionCount = domainAccess.getContext().fetchCount(STATUS_HISTORY, STATUS_HISTORY.ID.eq(ehrStatusId));

        return versionCount + 1;
    }

    @Override
    public void setStatus(EhrStatus status) {
        setModifiable(status.isModifiable());
        setQueryable(status.isQueryable());
        setOtherDetails(status.getOtherDetails(), null);
        String subjectId = status.getSubject().getExternalRef().getId().getValue();
        String subjectNamespace = status.getSubject().getExternalRef().getNamespace();

        UUID subjectUuid = I_PartyIdentifiedAccess.getOrCreatePartyByExternalRef(getDataAccess(), null, subjectId, BaseService.DEMOGRAPHIC, subjectNamespace, BaseService.PARTY);
        setParty(subjectUuid);
    }

    @Override
    public EhrStatus getStatus() {
        EhrStatus status = new EhrStatus();

        status.setModifiable(isModifiable());
        status.setQueryable(isQueryable());
        status.setOtherDetails((ItemStructure) getOtherDetails());
        status.setUid(new HierObjectId(statusRecord.getId().toString()));

        I_PartyIdentifiedAccess party = I_PartyIdentifiedAccess.retrieveInstance(getDataAccess(), getParty());

        PartySelf partySelf = new PartySelf(new PartyRef(new HierObjectId(party.getPartyRefValue()), party.getPartyRefNamespace(), null));
        status.setSubject(partySelf);


        return status;
    }

    public enum PARTY_MODE {IDENTIFIER, EXTERNAL_REF}
}