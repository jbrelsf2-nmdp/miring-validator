/*
    MiringValidator  Semantic Validator for MIRING compliant HML
    Copyright (c) 2014-2015 National Marrow Donor Program (NMDP)

    This library is free software; you can redistribute it and/or modify it
    under the terms of the GNU Lesser General Public License as published
    by the Free Software Foundation; either version 3 of the License, or (at
    your option) any later version.

    This library is distributed in the hope that it will be useful, but WITHOUT
    ANY WARRANTY; with out even the implied warranty of MERCHANTABILITY or
    FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public
    License for more details.

    You should have received a copy of the GNU Lesser General Public License
    along with this library;  if not, write to the Free Software Foundation,
    Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307  USA.

    > http://www.gnu.org/licenses/lgpl.html

*/
package main.java.miringvalidator;

import java.io.File;
import java.io.StringReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.validation.*;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

public class SchemaValidator
{
    private static final Logger logger = LogManager.getLogger(SchemaValidator.class);
    public static List<ValidationError> validationErrors;
    
    /**
     * Validate xml against a schema
     *
     * @param xml a String containing the XML to validate
     * @param schemaFileName the file name of the schema to compare against
     * @return an array of ValidationError objects found during validation
     */
    public static ValidationError[] validate(String xml, String schemaFileName) 
    {
        logger.debug("Starting a schema validation");        
        validationErrors = new ArrayList<ValidationError>();

        try 
        {
            URL schemaURL = SchemaValidator.class.getResource(schemaFileName);
            logger.debug("Schema URL Resource Location = " + schemaURL);
            File schemaFile = new File(schemaURL.toURI());
            Schema schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(schemaFile);

            final SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setSchema(schema);
            
            final SAXParser parser = factory.newSAXParser();
            final MiringValidationContentHandler handler = new MiringValidationContentHandler();

            //parser.parse is what does the actual "validation."  It parses the sample xml referring to the schema.
            //Errors are thrown by the handler, and we'll turn those into validation errors that are human readable.
            parser.parse(new InputSource(new StringReader(xml)), handler);
        } 
        catch (SAXException e) 
        {
            //We shouldn't get exceptions out here.  They should have been handled by the MiringValidationContentHandler
            logger.error("SaxException Error: " + e.getLocalizedMessage());
        }
        catch (Exception e)
        {
            logger.error("Exception during schema validation: " + e.getLocalizedMessage());
        }
        
        if(validationErrors.size() > 0)
        {
            //List -> Array
            ValidationError[] array = validationErrors.toArray(new ValidationError[validationErrors.size()]);
            logger.debug(validationErrors.size() + " schema validation errors found");
            return array;
        }
        else
        {
            logger.debug("ZERO schema validation errors found");
            //Empty.  Not null.  No problems found.
            return new ValidationError[0];
        }
    }

    private static class MiringValidationContentHandler extends DefaultHandler 
    {    
        //pseudoXPath and attributesLinkedList are used to keep track of where the parser is in the document.
        //pseudoXPath probably could  have indexes of the nodes for more information.  Look into this later.
        //attributesLinkedList is ordered, and the elements correspond to the pseudoXpath.
        //We will add and remove elements from these as the parser parses.
        private static String pseudoXPath = "";
        public static List<String> attributesLinkedList = new ArrayList<String>();

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException 
        {
            try
            {
                pseudoXPath = pseudoXPath + "/" + localName; 
                attributesLinkedList.add(Utilities.getAttributes(attributes));
            }
            catch(Exception e)
            {
                logger.error("Exception in startElement: " + e);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException 
        {
            try
            {
                pseudoXPath = pseudoXPath.substring( 0, pseudoXPath.lastIndexOf("/") );
                attributesLinkedList.remove(attributesLinkedList.size()-1);
            }
            catch(Exception e)
            {
                logger.error("Exception in endElement: " + e);
            }
        }
        
        //warning(), error(), and fatalError() are overrides which are triggered by 
        //parser warnings, errors and fatal errors.  
        //Here we provide simple handlers for the three.
        @Override
        public void warning(SAXParseException exception) throws SAXException 
        {
            logger.debug("Sax Parser Warning: " + exception.getMessage());
            handleParserException(exception);
        }
    
        @Override
        public void error(SAXParseException exception) throws SAXException 
        {
            logger.debug("Sax Parser NonFatal Error: " + exception.getMessage());
            handleParserException(exception);
        }
    
        @Override
        public void fatalError(SAXParseException exception) throws SAXException 
        {
            logger.debug("Sax Parser Fatal Error: " + exception.getMessage());
            handleParserException(exception);
        }

        private static void handleParserException(SAXException exception)
        {
            //I'm calling this method when we get a legitimate SAX Parser exception, which are triggered
            //When the parser finds a problem with the xml            
            //Take the SAX parser exception, tokenize it, and build ValidationError objects based on the errors.

            ValidationError ve = null;
            
            String errorMessage = exception.getMessage();
            String[] exceptionTokens = tokenizeString(errorMessage);
            
            if(errorMessage.equals("Content is not allowed in prolog."))
            {
                ve = new ValidationError("Content is not allowed in prolog.",true);
                ve.setSolutionText("This most likely means that there is some text before the initial xml node begins.  Get rid of it and try again." );
            }            
            else if(exceptionTokens[0].equals("cvc-complex-type.2.4.a:") || exceptionTokens[0].equals("cvc-complex-type.2.4.b:"))
            {
                // This cvc-complex-type is called if there is a node missing.  here's a few examples of what the exception.getMessage() can look like
                // cvc-complex-type.2.4.a: Invalid content was found starting with element 'sample'. One of '{"http://schemas.nmdp.org/spec/hml/1.0.1":property, "http://schemas.nmdp.org/spec/hml/1.0.1":hmlid}' is expected.
                // cvc-complex-type.2.4.a: Invalid content was found starting with element 'sample'. One of '{"http://schemas.nmdp.org/spec/hml/1.0.1":reporting-center}' is expected.
                // cvc-complex-type.2.4.a: Invalid content was found starting with element 'reporting-center'. One of '{"http://schemas.nmdp.org/spec/hml/1.0.1":property, "http://schemas.nmdp.org/spec/hml/1.0.1":hmlid}' is expected.
                // cvc-complex-type.2.4.b: The content of element 'sbt-ngs' is not complete. One of '{"http://schemas.nmdp.org/spec/hml/1.0.1":property, "http://schemas.nmdp.org/spec/hml/1.0.1":raw-reads}' is expected.
                // for 2.4.b, it's interesting that it says the content of element 'sbt-ngs'.  That's the real parent of the node where it's missing.  Perhaps I can use that info, maybe it's useless.

                // The missing node name ("hmlid", "reporting-center", etc.) will be the last word between the '{' and '}'. 
                // Find their indices.
                int minInd = -1, maxInd = -1;
                for(int x = 0; x < exceptionTokens.length; x++)
                {
                    String token = exceptionTokens[x];
                    if(token.contains("{"))
                    {
                        minInd = x;
                    }
                    if(token.contains("}"))
                    {
                        maxInd = x;
                    }
                }
                
                if(minInd == -1 || maxInd == -1)
                {
                    logger.error("parsing a cvc-complex-type.2.4.a schema .  No Indices Found.");
                }
                // qualifiedNodeName should look like this:
                // "http://schemas.nmdp.org/spec/hml/1.0.1":hmlid}'
                String qualifiedNodeName = exceptionTokens[maxInd];
                int begIndex = 11 + qualifiedNodeName.indexOf("hml/1.0.1\":");
                int enDex = qualifiedNodeName.indexOf("}'");
                
                String missingNodeName = qualifiedNodeName.substring(begIndex, enDex);
                
                ve = handleMissingNode(missingNodeName);
            }
            else if(exceptionTokens[0].equals("cvc-complex-type.4:"))
            {
                //This cvc-complex-type is called if there is an attribute missing from a node
                //It looks like this:
                // cvc-complex-type.4: Attribute 'quality-score' must appear on element 'variant'.
                
                String missingAttributeName = exceptionTokens[2].replace("'", "");
                String untrimmedNodeName = exceptionTokens[7];                
                String nodeName = untrimmedNodeName.substring(1, untrimmedNodeName.indexOf("'."));
                
                ve = handleMissingAttribute(missingAttributeName, nodeName);
            }
            else
            {
                //There are more types of errors probably. 
                //What if I have too many of a thing?
                logger.error("This Sax Parser Exception isn't handled :" + exception.getMessage());
            }
            
           
            ve.xPath = pseudoXPath;
            Utilities.addValidationError(validationErrors, ve);
        }

        private static ValidationError handleMissingAttribute(String missingAttributeName, String nodeName)
        {
            ValidationError ve;
            String miringRuleID = "Unhandled Miring Rule ID";
            String errorMessage = "The node " + nodeName + " is missing a " + missingAttributeName + " attribute.";
            String solutionMessage = "Please add a " + missingAttributeName + " attribute to the " + nodeName + " node.";
            String moreInformation = "";
            
            //Specific logic for various MIRING rules
            //I think I have access to lots more information than what I'm putting here.
            //Look into the parent node, we can probably get it's attributes to be useful here.
            //moreInformation = moreInformation + (any useful information we can find)
            //I could make a getMiringRuleID Method.  Yeah that would be smart.  Use Case Statements.
            //TODO: Make that getMiringRuleID method.
            if(nodeName.equals("variant"))
            {
                if(missingAttributeName.equals("id"))
                {
                    miringRuleID = "5.3.a";
                }
                else if(missingAttributeName.equals("reference-bases"))
                {
                    miringRuleID = "5.4.a";
                }
                else if(missingAttributeName.equals("alternate-bases"))
                {
                    miringRuleID = "5.5.a";
                }
                else if(missingAttributeName.equals("quality-score"))
                {
                    miringRuleID = "5.6.a";
                }
                else if(missingAttributeName.equals("filter"))
                {
                    miringRuleID = "5.7.a";
                }
                else
                {
                    logger.error("Missing attribute name not handled! : " + missingAttributeName);
                }
            }
            else if(nodeName.equals("allele-assignment"))
            {
                if(missingAttributeName.equals("allele-db"))
                {
                    miringRuleID = "2.1.b";
                }
                else if(missingAttributeName.equals("allele-version"))
                {
                    miringRuleID = "2.1.c";
                }
                else
                {
                    logger.error("Missing attribute name not handled! : " + missingAttributeName);
                }
            }
            else if(nodeName.equals("raw-reads"))
            {
                if(missingAttributeName.equals("availability"))
                {
                    miringRuleID = "1.5.b";
                    moreInformation = moreInformation + ("availability attribute can be one of: public, private, or permission.  ");
                }
                else
                {
                    logger.error("Missing attribute name not handled! : " + missingAttributeName);
                }
            }
            else if(nodeName.equals("sbt-ngs"))
            {
                if(missingAttributeName.equals("test-id") || missingAttributeName.equals("test-id-source"))
                {
                    miringRuleID = "1.3.a";
                    moreInformation = moreInformation + ("test-id and test-id-source should refer to a valid NCBI-GRT procedure.  ");
                }
                else
                {
                    logger.error("Missing attribute name not handled! : " + missingAttributeName);
                }
            }            
            else if(nodeName.equals("reference-database"))
            {
                if(missingAttributeName.equals("curated"))
                {
                    miringRuleID = "2.3.b";
                }
                else
                {
                    logger.error("Missing attribute name not handled! : " + missingAttributeName);
                }
            }
            else if(nodeName.equals("consensus-sequence-block"))
            {
                logger.error("consensus-sequence-block not handled! : " + missingAttributeName);
            }
            else if(nodeName.equals("reference-sequence"))
            {
                if(missingAttributeName.equals("id")
                    || missingAttributeName.equals("name")
                    || missingAttributeName.equals("start")
                    || missingAttributeName.equals("end")
                    || missingAttributeName.equals("accession"))
                {
                    miringRuleID = "2.2.b";
                }
                else
                {
                    logger.error("Missing attribute name not handled! : " + missingAttributeName);
                }
            }
            else
            {
                logger.error("Node Name Not Handled! : " + nodeName);
            }
            
            ve =  new ValidationError(errorMessage,true);
            ve.setSolutionText(solutionMessage);
            ve.setMiringRule(miringRuleID);
            ve.addMoreInformation(moreInformation);
            return ve;
        }

        private static ValidationError handleMissingNode(String missingNodeName)
        {
            String errorMessage = "Unhandled Error Message";
            String solutionMessage = "Unhandled Solution Message";
            String moreInformation = "";
            String miringRuleID = "Unhandled Miring Rule ID";
            String parentNodeName = "Unhandled Parent Node Name";
            String parentAttributes = null;
            ValidationError ve;
            
            //the last node name on this xpath is the parent node name.
            if(pseudoXPath != null && pseudoXPath.length() > 0 && pseudoXPath.contains("/")) 
            {
                parentNodeName = pseudoXPath.substring( pseudoXPath.lastIndexOf("/") + 1, pseudoXPath.length());
            }
            else
            {
                logger.error("No parent node found for missingNodeName=" + missingNodeName);
            }
            
            //The top element on this list should be the parent attributes
            if(attributesLinkedList != null && attributesLinkedList.size() > 0)
            {
                parentAttributes = attributesLinkedList.get(attributesLinkedList.size()-1);
            }
            else
            {
                logger.error("No parent attributes found for missingNodeName=" + missingNodeName);
            }
            
            
            //Default error message and solution.  Might replace this later depending on node.
            errorMessage = "There is a missing " + missingNodeName + " node underneath the " + parentNodeName + " node.";
            solutionMessage = "Please add exactly one " + missingNodeName + " node underneath the " + parentNodeName + " node.";
            
            //Specific logic for various MIRING errors
            if(missingNodeName.equals("hmlid"))
            {
                miringRuleID = "1.1.a";
            }
            else if(missingNodeName.equals("reporting-center"))
            {
                miringRuleID = "1.2.a";
            }
            else if(missingNodeName.equals("raw-reads"))
            {
                miringRuleID = "1.5.a";
                solutionMessage = "Every sbt-ngs node must have at least one child raw-reads node.";
            }
            else if(missingNodeName.equals("allele-assignment"))
            {
                miringRuleID = "2.1.a";
                solutionMessage = "Every typing node must have at least one child allele-assignment node.";
            }
            else
            {                
                logger.error("MissingNodeName Not Handled: " + missingNodeName);
            }

            ve =  new ValidationError(errorMessage,true);
            ve.setSolutionText(solutionMessage);
            ve.setMiringRule(miringRuleID);

            moreInformation = moreInformation + "Parent node " + parentNodeName + " has these attributes: " + parentAttributes;
            ve.addMoreInformation(moreInformation);

            return ve;
        }

        private static String[] tokenizeString(String exceptionMessage)
        {
            try
            {
                StringTokenizer st = new StringTokenizer(exceptionMessage);
                String[] messageTokens = new String[st.countTokens()];
                int counter = 0;
                while (st.hasMoreTokens()) 
                {
                    messageTokens[counter] = st.nextToken();
                    counter++;
                }
                return messageTokens;
            }
            catch(Exception e)
            {
                logger.error("Exception in tokenizeString(): " + e);
                return null;
            }
        }
    }
}