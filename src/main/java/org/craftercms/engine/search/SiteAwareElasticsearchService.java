/*
 * Copyright (C) 2007-2020 Crafter Software Corporation. All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as published by
 * the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.craftercms.engine.search;

import org.craftercms.search.elasticsearch.impl.AbstractElasticsearchWrapper;
import org.craftercms.engine.service.context.SiteContext;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import static java.util.stream.Collectors.joining;
import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;
import static org.elasticsearch.index.query.QueryBuilders.boolQuery;
import static org.elasticsearch.index.query.QueryBuilders.existsQuery;
import static org.elasticsearch.index.query.QueryBuilders.matchQuery;

/**
 * Implementation of {@link org.craftercms.search.elasticsearch.ElasticsearchWrapper}
 * that sets the index based on the current site context for all search requests.
 * @author joseross
 * @since 3.1
 */
public class SiteAwareElasticsearchService extends AbstractElasticsearchWrapper {

    private static final Logger logger = LoggerFactory.getLogger(SiteAwareElasticsearchService.class);

    private static final String DEFAULT_ROLE_FIELD_NAME = "authorizedRoles.item.role";

    /**
     * Format used to build the index id
     */
    protected String indexIdFormat;

    protected String roleFieldName = DEFAULT_ROLE_FIELD_NAME;

    public SiteAwareElasticsearchService(RestHighLevelClient client, String indexIdFormat) {
        super(client);
        this.indexIdFormat = indexIdFormat;
    }

    public void setRoleFieldName(final String roleFieldName) {
        this.roleFieldName = roleFieldName;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void updateIndex(final SearchRequest request) {
        SiteContext siteContext = SiteContext.getCurrent();
        if (siteContext != null) {
            request.indices(String.format(indexIdFormat, siteContext.getSiteName()));
        } else {
            throw new IllegalStateException("Current site context not found");
        }
    }

    @Override
    protected void updateFilters(final SearchRequest request) {
        super.updateFilters(request);

        BoolQueryBuilder mainQuery = (BoolQueryBuilder) request.source().query();

        Authentication auth = null;
        SecurityContext context = SecurityContextHolder.getContext();
        if (context != null) {
            auth = context.getAuthentication();
        }

        // Include all public items
        BoolQueryBuilder securityQuery = boolQuery()
                .should(boolQuery().mustNot(existsQuery(roleFieldName)))
                .should(matchQuery(roleFieldName, "anonymous"));

        if (auth != null && !(auth instanceof AnonymousAuthenticationToken) && isNotEmpty(auth.getAuthorities())) {
            logger.debug("Filtering search results for roles: {}", auth.getAuthorities());
            securityQuery.should(matchQuery(roleFieldName, auth.getAuthorities().stream()
                            .map(Object::toString)
                            .collect(joining(" "))));
        } else {
            logger.debug("Filtering search to show only public items");
        }

        mainQuery.filter(boolQuery().must(securityQuery));
    }

}
