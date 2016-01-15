/*******************************************************************************
 *     Cloud Foundry
 *     Copyright (c) [2009-2014] Pivotal Software, Inc. All Rights Reserved.
 *
 *     This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *     You may not use this product except in compliance with the License.
 *
 *     This product includes a number of subcomponents with
 *     separate copyright notices and license terms. Your use of these
 *     subcomponents is subject to the terms and conditions of the
 *     subcomponent's license, as noted in the LICENSE file.
 *******************************************************************************/
package org.cloudfoundry.identity.uaa.login.saml.idp;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.cloudfoundry.identity.uaa.util.JsonUtils;
import org.cloudfoundry.identity.uaa.util.ObjectUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

public class JdbcSamlServiceProviderProvisioning implements SamlServiceProviderProvisioning {

    public static final String SERVICE_PROVIDER_FIELDS = "id,version,created,lastmodified,name,entity_id,config,identity_zone_id,active";

    public static final String CREATE_SERVICE_PROVIDER_SQL = "insert into service_provider(" + SERVICE_PROVIDER_FIELDS + ") values (?,?,?,?,?,?,?,?,?)";

    public static final String DELETE_SERVICE_PROVIDER_SQL = "delete from service_provider where id=?";

    public static final String SERVICE_PROVIDERS_QUERY = "select " + SERVICE_PROVIDER_FIELDS + " from service_provider where identity_zone_id=?";

    public static final String ACTIVE_SERVICE_PROVIDERS_QUERY = SERVICE_PROVIDERS_QUERY + " and active";

    public static final String SERVICE_PROVIDER_UPDATE_FIELDS = "version,lastmodified,name,config,active".replace(",", "=?,") + "=?";

    public static final String UPDATE_SERVICE_PROVIDER_SQL = "update service_provider set " + SERVICE_PROVIDER_UPDATE_FIELDS + " where id=?";

    public static final String SERVICE_PROVIDER_BY_ID_QUERY = "select " + SERVICE_PROVIDER_FIELDS + " from service_provider " + "where id=?";

    public static final String SERVICE_PROVIDER_BY_ENTITY_ID_QUERY = "select " + SERVICE_PROVIDER_FIELDS + " from service_provider " + "where entity_id=? and identity_zone_id=? ";

    protected final JdbcTemplate jdbcTemplate;

    private final RowMapper<SamlServiceProvider> mapper = new SamlServiceProviderRowMapper();

    public JdbcSamlServiceProviderProvisioning(JdbcTemplate jdbcTemplate) {
        Assert.notNull(jdbcTemplate);
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public SamlServiceProvider retrieve(String id) {
        SamlServiceProvider serviceProvider = jdbcTemplate.queryForObject(SERVICE_PROVIDER_BY_ID_QUERY, mapper, id);
        return serviceProvider;
    }

    @Override
    public void delete(String id) {
        jdbcTemplate.update(DELETE_SERVICE_PROVIDER_SQL, id);
    }

    @Override
    public List<SamlServiceProvider> retrieveActive(String zoneId) {
        return jdbcTemplate.query(ACTIVE_SERVICE_PROVIDERS_QUERY, mapper, zoneId);
    }

    @Override
    public List<SamlServiceProvider> retrieveAll(boolean activeOnly, String zoneId) {
        if (activeOnly) {
            return retrieveActive(zoneId);
        } else {
            return jdbcTemplate.query(SERVICE_PROVIDERS_QUERY, mapper, zoneId);
        }
    }

    @Override
    public SamlServiceProvider retrieveByOrigin(String origin, String zoneId) {
        SamlServiceProvider serviceProvider = jdbcTemplate.queryForObject(SERVICE_PROVIDER_BY_ENTITY_ID_QUERY, mapper, origin, zoneId);
        return serviceProvider;
    }

    @Override
    public SamlServiceProvider create(final SamlServiceProvider serviceProvider) {
        validate(serviceProvider);
        final String id = UUID.randomUUID().toString();
        try {
            jdbcTemplate.update(CREATE_SERVICE_PROVIDER_SQL, new PreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps) throws SQLException {
                    int pos = 1;
                    ps.setString(pos++, id);
                    ps.setInt(pos++, serviceProvider.getVersion());
                    ps.setTimestamp(pos++, new Timestamp(System.currentTimeMillis()));
                    ps.setTimestamp(pos++, new Timestamp(System.currentTimeMillis()));
                    ps.setString(pos++, serviceProvider.getName());
                    ps.setString(pos++, serviceProvider.getEntityId());
                    ps.setString(pos++, JsonUtils.writeValueAsString(serviceProvider.getConfig()));
                    ps.setString(pos++, serviceProvider.getIdentityZoneId());
                    ps.setBoolean(pos++, serviceProvider.isActive());
                }
            });
        } catch (DuplicateKeyException e) {
            throw new SamlSpAlreadyExistsException(e.getMostSpecificCause().getMessage());
        }
        return retrieve(id);
    }

    @Override
    public SamlServiceProvider update(final SamlServiceProvider serviceProvider) {
        validate(serviceProvider);
        jdbcTemplate.update(UPDATE_SERVICE_PROVIDER_SQL, new PreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps) throws SQLException {
                int pos = 1;
                ps.setInt(pos++, serviceProvider.getVersion() + 1);
                ps.setTimestamp(pos++, new Timestamp(new Date().getTime()));
                ps.setString(pos++, serviceProvider.getName());
                ps.setString(pos++, JsonUtils.writeValueAsString(serviceProvider.getConfig()));
                ps.setBoolean(pos++, serviceProvider.isActive());
                ps.setString(pos++, serviceProvider.getId().trim());
            }
        });
        return retrieve(serviceProvider.getId());
    }

    protected void validate(SamlServiceProvider provider) {
        if (provider == null) {
            throw new NullPointerException("SAML Service Provider can not be null.");
        }
        if (!StringUtils.hasText(provider.getIdentityZoneId())) {
            throw new DataIntegrityViolationException("Identity zone ID must be set.");
        }

        SamlServiceProviderDefinition saml = ObjectUtils.castInstance(provider.getConfig(),
                SamlServiceProviderDefinition.class);
        saml.setSpEntityId(provider.getEntityId());
        saml.setZoneId(provider.getIdentityZoneId());
        provider.setConfig(saml);
    }

    private static final class SamlServiceProviderRowMapper implements RowMapper<SamlServiceProvider> {
        public SamlServiceProviderRowMapper() {
            // Default constructor.
        }

        @Override
        public SamlServiceProvider mapRow(ResultSet rs, int rowNum) throws SQLException {
            SamlServiceProvider samlServiceProvider = new SamlServiceProvider();
            int pos = 1;
            samlServiceProvider.setId(rs.getString(pos++).trim());
            samlServiceProvider.setVersion(rs.getInt(pos++));
            samlServiceProvider.setCreated(rs.getTimestamp(pos++));
            samlServiceProvider.setLastModified(rs.getTimestamp(pos++));
            samlServiceProvider.setName(rs.getString(pos++));
            samlServiceProvider.setEntityId(rs.getString(pos++));
            String config = rs.getString(pos++);
            SamlServiceProviderDefinition definition = JsonUtils.readValue(config, SamlServiceProviderDefinition.class);
            samlServiceProvider.setConfig(definition);
            samlServiceProvider.setIdentityZoneId(rs.getString(pos++));
            samlServiceProvider.setActive(rs.getBoolean(pos++));
            return samlServiceProvider;
        }
    }

}
