//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.client;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.AuthenticationStore;

public class HttpAuthenticationStore implements AuthenticationStore
{
    private final List<Authentication> authentications = new CopyOnWriteArrayList<>();
    private final Map<String, Authentication> results = new ConcurrentHashMap<>();

    @Override
    public void addAuthentication(Authentication authentication)
    {
        authentications.add(authentication);
    }

    @Override
    public void removeAuthentication(Authentication authentication)
    {
        authentications.remove(authentication);
    }

    @Override
    public Authentication findAuthentication(String type, String uri, String realm)
    {
        for (Authentication authentication : authentications)
        {
            if (authentication.matches(type, uri, realm))
                return authentication;
        }
        return null;
    }

    @Override
    public void addAuthenticationResult(Authentication.Result result)
    {
        results.put(result.getURI(), result.getAuthentication());
    }

    @Override
    public void removeAuthenticationResults()
    {
        results.clear();
    }

    @Override
    public Authentication findAuthenticationResult(String uri)
    {
        // TODO: I should match the longest URI
        for (Map.Entry<String, Authentication> entry : results.entrySet())
        {
            if (uri.startsWith(entry.getKey()))
                return entry.getValue();
        }
        return null;
    }
}