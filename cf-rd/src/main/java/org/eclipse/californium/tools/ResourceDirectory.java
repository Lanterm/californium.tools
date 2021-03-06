/*******************************************************************************
 * Copyright (c) 2014 Institute for Pervasive Computing, ETH Zurich and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *    Matthias Kovatsch - creator and main architect
 ******************************************************************************/
package org.eclipse.californium.tools;

import java.net.SocketException;

import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.tools.resources.RDLookUpTopResource;
import org.eclipse.californium.tools.resources.RDResource;
import org.eclipse.californium.tools.resources.RDTagTopResource;

/**
 * The class ResourceDirectory provides an experimental RD
 * as described in draft-ietf-core-resource-directory-00.
 */
public class ResourceDirectory extends CoapServer {
    
    // exit codes for runtime errors
    public static final int ERR_INIT_FAILED = 1;
    
    public static void main(String[] args) {
        
        // create server
        try {
            
            CoapServer server = new ResourceDirectory();
            server.start();
            
            System.out.printf(ResourceDirectory.class.getSimpleName()+" listening on port %d.\n", server.getEndpoints().get(0).getAddress().getPort());
            
        } catch (SocketException e) {
            
            System.err.printf("Failed to create "+ResourceDirectory.class.getSimpleName()+": %s\n", e.getMessage());
            System.exit(ERR_INIT_FAILED);
        }
        
    }
    
    /**
     * Constructor for a new ResourceDirectory. Call {@code super(...)} to configure
     * the port, etc. according to the {@link LocalEndpoint} constructors.
     * <p>
     * Add all initial {@link LocalResource}s here.
     */
    public ResourceDirectory() throws SocketException {
        
    	RDResource rdResource = new RDResource(); 

        // add resources to the server
		add(rdResource);
		add(new RDLookUpTopResource(rdResource));
		add(new RDTagTopResource(rdResource));
    }
}
