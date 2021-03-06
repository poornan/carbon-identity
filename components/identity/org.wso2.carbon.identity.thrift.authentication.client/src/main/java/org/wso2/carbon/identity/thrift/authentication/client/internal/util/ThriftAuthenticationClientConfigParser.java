/*
*  Copyright (c) 2005-2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.wso2.carbon.identity.thrift.authentication.client.internal.util;

import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.impl.builder.StAXOMBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.base.ServerConfigurationException;
import org.wso2.carbon.utils.CarbonUtils;
import org.wso2.carbon.utils.ServerConstants;
import org.wso2.securevault.SecretResolver;
import org.wso2.securevault.SecretResolverFactory;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import java.io.*;
import java.util.*;

/**
 * thrift-authentication.xml passer
 */
public class ThriftAuthenticationClientConfigParser {

    public static final String THRIFT_AUTHENTICATION_CLIENT_CONFIG = "thrift-authentication-client.xml";
    public static final String IDENTITY_DEFAULT_NAMESPACE = "http://wso2.org/projects/carbon/carbon.xml";
    private static Map<String, Object> configuration = new HashMap<String, Object>();
    private static ThriftAuthenticationClientConfigParser parser;
    private static SecretResolver secretResolver;
    // To enable attempted thread-safety using double-check locking
    private static Object lock = new Object();
    private static Log log = LogFactory.getLog(ThriftAuthenticationClientConfigParser.class);
    private static String configFilePath;

    private OMElement rootElement;

    private ThriftAuthenticationClientConfigParser() throws ServerConfigurationException {
        try {
            buildConfiguration();
        } catch (Exception e) {
            log.error("Error while loading Identity Configurations", e);
            throw new ServerConfigurationException("Error while loading Identity Configurations", e);
        }
    }

	public static ThriftAuthenticationClientConfigParser getInstance() throws ServerConfigurationException {
		if (parser == null) {
			synchronized (lock) {
				if (parser == null) {
					parser = new ThriftAuthenticationClientConfigParser();
				}
			}
		}
		return parser;
	}

    public static ThriftAuthenticationClientConfigParser getInstance(String filePath)
            throws ServerConfigurationException {
        configFilePath = filePath;
        return getInstance();
    }

    public Map<String, Object> getConfiguration() {
        return configuration;
    }

    /**
     * @return
     * @throws javax.xml.stream.XMLStreamException
     * @throws java.io.IOException
     */
    private void buildConfiguration() throws XMLStreamException, IOException {
        InputStream inStream = null;
        StAXOMBuilder builder = null;

        String warningMessage = "";
        try {
            if (configFilePath != null) {
                File identityConfigXml = new File(configFilePath);
                if (identityConfigXml.exists()) {
                    inStream = new FileInputStream(identityConfigXml);
                }
            } else {

                File identityConfigXml = new File(CarbonUtils.getCarbonConfigDirPath(), THRIFT_AUTHENTICATION_CLIENT_CONFIG);
                if (identityConfigXml.exists()) {
                    inStream = new FileInputStream(identityConfigXml);
                }
            }

            if (inStream == null) {
                String message = "Thrift Authentication Client configuration not found. Cause - " + warningMessage;
                if (log.isDebugEnabled()) {
                    log.debug(message);
                }
                throw new FileNotFoundException(message);
            }

            builder = new StAXOMBuilder(inStream);
            rootElement = builder.getDocumentElement();
            Stack<String> nameStack = new Stack<String>();
            secretResolver = SecretResolverFactory.create(rootElement, true);
            readChildElements(rootElement, nameStack);

        } finally {
            try {
                if (inStream != null) {
                    inStream.close();
                }
            } catch (IOException e) {
                log.warn("Error closing the input stream.", e);
            }
        }
    }

    private void readChildElements(OMElement serverConfig, Stack<String> nameStack) {
        for (Iterator childElements = serverConfig.getChildElements(); childElements.hasNext(); ) {
            OMElement element = (OMElement) childElements.next();
            nameStack.push(element.getLocalName());
            if (elementHasText(element)) {
                String key = getKey(nameStack);
                Object currentObject = configuration.get(key);
                String value = replaceSystemProperty(element.getText());
                if(secretResolver != null && secretResolver.isInitialized() &&
                                                    secretResolver.isTokenProtected(key)){
                    value = secretResolver.resolve(key);
                }
                if (currentObject == null) {
                    configuration.put(key, value);
                } else if (currentObject instanceof ArrayList) {
                    ArrayList list = (ArrayList) currentObject;
                    if (!list.contains(value)) {
                        list.add(value);
                    }
                } else {
                    if (!value.equals(currentObject)) {
                        ArrayList arrayList = new ArrayList(2);
                        arrayList.add(currentObject);
                        arrayList.add(value);
                        configuration.put(key, arrayList);
                    }
                }
            }
            readChildElements(element, nameStack);
            nameStack.pop();
        }
    }

    private String getKey(Stack<String> nameStack) {
        StringBuffer key = new StringBuffer();
        for (int i = 0; i < nameStack.size(); i++) {
            String name = nameStack.elementAt(i);
            key.append(name).append(".");
        }
        key.deleteCharAt(key.lastIndexOf("."));

        return key.toString();
    }

    private boolean elementHasText(OMElement element) {
        String text = element.getText();
        return text != null && text.trim().length() != 0;
    }

    private String replaceSystemProperty(String text) {
        int indexOfStartingChars = -1;
        int indexOfClosingBrace;

        // The following condition deals with properties.
        // Properties are specified as ${system.property},
        // and are assumed to be System properties
        while (indexOfStartingChars < text.indexOf("${")
                && (indexOfStartingChars = text.indexOf("${")) != -1
                && (indexOfClosingBrace = text.indexOf("}")) != -1) { // Is a property used?
            String sysProp = text.substring(indexOfStartingChars + 2, indexOfClosingBrace);
            String propValue = System.getProperty(sysProp);
            if (propValue != null) {
                text = text.substring(0, indexOfStartingChars) + propValue
                        + text.substring(indexOfClosingBrace + 1);
            }
            if (sysProp.equals(ServerConstants.CARBON_HOME)) {
                if (System.getProperty(ServerConstants.CARBON_HOME).equals(".")) {
                    text = new File(".").getAbsolutePath() + File.separator + text;
                }
            }
        }
        return text;
    }

    /**
     * Returns the element with the provided local part
     *
     * @param localPart local part name
     * @return Corresponding OMElement
     */
    public OMElement getConfigElement(String localPart) {
        return rootElement.getFirstChildWithName(new QName(IDENTITY_DEFAULT_NAMESPACE, localPart));
    }

}
