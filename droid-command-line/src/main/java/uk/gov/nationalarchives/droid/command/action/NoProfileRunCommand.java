/**
 * <p>Copyright (c) The National Archives 2005-2010.  All rights reserved.
 * See Licence.txt for full licence details.
 * <p/>
 *
 * <p>DROID DCS Profile Tool
 * <p/>
 */
package uk.gov.nationalarchives.droid.command.action;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.ResourceBundle;

import org.apache.commons.io.FileUtils;

import uk.gov.nationalarchives.droid.command.container.ContainerContentIdentifierFactory;
import uk.gov.nationalarchives.droid.command.container.ContainerContentIdentifier;
import uk.gov.nationalarchives.droid.container.ContainerSignatureDefinitions;
import uk.gov.nationalarchives.droid.container.ContainerSignatureSaxParser;
import uk.gov.nationalarchives.droid.container.TriggerPuid;
import uk.gov.nationalarchives.droid.core.BinarySignatureIdentifier;
import uk.gov.nationalarchives.droid.core.interfaces.IdentificationRequest;
import uk.gov.nationalarchives.droid.core.interfaces.IdentificationResult;
import uk.gov.nationalarchives.droid.core.interfaces.IdentificationResultCollection;
import uk.gov.nationalarchives.droid.core.interfaces.RequestIdentifier;
import uk.gov.nationalarchives.droid.core.interfaces.resource.FileSystemIdentificationRequest;
import uk.gov.nationalarchives.droid.core.interfaces.resource.RequestMetaData;
import uk.gov.nationalarchives.droid.core.SignatureParseException;
import uk.gov.nationalarchives.droid.core.interfaces.archive.ArchiveFormatResolver;

/**
 * @author rbrennan
 *
 */
public class NoProfileRunCommand implements DroidCommand {

    private String signatureFile;
    private String containerSignatureFile;
    private String[] resources;
    private String[] extensions;
    private boolean quietFlag = false;  // default quiet flag value
    private boolean recursive;
    private BinarySignatureIdentifier binarySignatureIdentifier;
    private ContainerSignatureSaxParser contSigParser;
    private List<TriggerPuid> triggerPuid;
    private ContainerSignatureDefinitions containerSignatureDefinitions;
    private ContainerContentIdentifierFactory containerContentIdentifierFactory;

    /**
    * {@inheritDoc}
    */
    @Override
    public void execute() throws CommandExecutionException {
        
        if(!this.quietFlag)
            this.outputRuntimeInformation();
      
        File dirToSearch = new File(resources[0]);
        if (!dirToSearch.isDirectory())
            throw new CommandExecutionException("Resources directory not found");

        binarySignatureIdentifier = new BinarySignatureIdentifier();
        File sigFile = new File(signatureFile);
        if (!sigFile.exists())
            throw new CommandExecutionException("Signature file not found");

        binarySignatureIdentifier.setSignatureFile(signatureFile);
        try {
            binarySignatureIdentifier.init();
        } catch (SignatureParseException e) {
            throw new CommandExecutionException ("Can't parse signature file");
        }
        binarySignatureIdentifier.setMaxBytesToScan(-1);
      
        if (this.containerSignatureFile != null) {
            File contSigFile = new File(containerSignatureFile);
            if (!contSigFile.exists()) {
                throw new CommandExecutionException("Container signature file not found");
            }
            try {
                InputStream in = new FileInputStream(contSigFile);
                contSigParser = new ContainerSignatureSaxParser();
                containerSignatureDefinitions = contSigParser.parse(in);
                triggerPuid = containerSignatureDefinitions.getTiggerPuids();
            } catch (SignatureParseException e) {
                throw new CommandExecutionException ("Can't parse container signature file");
            } catch (Exception e) {
                throw new CommandExecutionException(e);
            }
        }
        Collection<File> matchedFiles = FileUtils.listFiles(dirToSearch,
                  this.extensions, this.recursive);
        for (File file : matchedFiles) {
            URI resourceUri = file.toURI();

            RequestMetaData metaData = new RequestMetaData(file.length(),
                     file.lastModified(), file.getName());
            RequestIdentifier identifier = new RequestIdentifier(resourceUri);
            identifier.setParentId(1L);

            IdentificationRequest request = null;
            InputStream in = null;
            try {
                request = new FileSystemIdentificationRequest(metaData, identifier);
                
                try {
                    in = new FileInputStream(file);
                    request.open(in);
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (IOException e) {
                            throw new CommandExecutionException(e);
                        }
                    }
                }
                IdentificationResultCollection results =
                     binarySignatureIdentifier.matchBinarySignatures(request);

                IdentificationResultCollection containerResults = new IdentificationResultCollection(request);
                
                if (results.getResults().size() > 0) {
                    for (IdentificationResult identResult : results.getResults()) {
                        String puid = identResult.getPuid();
                        if (puid != null) {
                            if (this.containerSignatureFile != null) {
                                final TriggerPuid containerPuid = getTriggerPuidByPuid(puid);
                                if (containerPuid != null) {
                                    final ContainerContentIdentifier containerIdentifier =
                                            getContainerContentIdentifierFactory()
                                            .getContainerContentIdentifier(containerPuid.getContainerType(), containerSignatureDefinitions);
                                    containerResults = containerIdentifier.process(file, containerResults);
                                }
                            }
                        }
                    }
                }
                IdentificationResultCollection finalResults = new IdentificationResultCollection(request);
                if (containerResults.getResults().size() > 0) {
                    finalResults = containerResults;
                } else if (results.getResults().size() > 0) {
                    finalResults = results;
                }
                if (finalResults.getResults().size() > 0) {
                    binarySignatureIdentifier.removeLowerPriorityHits(finalResults);
                }
                if (finalResults.getResults().size() > 0) {
                    for (IdentificationResult identResult : finalResults.getResults()) {
                        System.out.println(file.getAbsolutePath() + "," + identResult.getPuid());
                    }   
                } else {
                    System.out.println(file.getAbsolutePath() + ",Unknown");
                }
            } catch (IOException e) {
                throw new CommandExecutionException(e);
            } finally {
                if (request != null) {
                    try {
                        request.close();
                    } catch (IOException e) {
                        throw new CommandExecutionException(e);
                    }
                }
            }
        }
    }

    private TriggerPuid getTriggerPuidByPuid(final String puid) {
        for (final TriggerPuid tp : triggerPuid) {
            if (tp.getPuid().equals(puid)) {
                return tp;
            }
        }
        return null;
    }
    
    /**
     * @param resources the resources to set
     */
    public void setResources(String[] resources) {
        this.resources = resources;
    }
    
    public void setSignatureFile(String signatureFile) {
        this.signatureFile = signatureFile;
    }

    public void setContainerSignatureFile(String containerSignatureFile) {
        this.containerSignatureFile = containerSignatureFile;
    }

    public void setRecursive(boolean recursive) {
        this.recursive = recursive;
    }
    
    public void setExtensionFilter(String[] extensions) {
        // No need to normalize extensions arr if empty, listFiles accepts null value 
        this.extensions = extensions;
    }
    
    public void setQuiet(boolean quiet) {
        this.quietFlag = quiet;
    }
    
    private void outputRuntimeInformation() {
        String versionString = ResourceBundle.getBundle("options").getString("version_no");
        System.out.println("DROID " + versionString + " No Profile mode: Runtime Information");
        System.out.println("Binary signature file: " + this.signatureFile);
       
        if (this.containerSignatureFile == null)
            System.out.println("Container signature file: None");
        else
            System.out.println("Container signature file: " + this.containerSignatureFile);
       
        if (this.extensions == null)
            System.out.println("Extension filter: No filter set");
        else
            System.out.println("Extension filter: " + Arrays.toString(this.extensions).replace("[", "").replace("]", "").trim());
        
        
        if (this.recursive == false)
           System.out.println("Recurse folders: False");
        else
           System.out.println("Recurse folders: True");
        
    }

    public ContainerContentIdentifierFactory getContainerContentIdentifierFactory() {
        return containerContentIdentifierFactory;
    }

    public void setContainerContentIdentifierFactory(ContainerContentIdentifierFactory containerContentIdentifierFactory) {
        this.containerContentIdentifierFactory = containerContentIdentifierFactory;
    }
}
