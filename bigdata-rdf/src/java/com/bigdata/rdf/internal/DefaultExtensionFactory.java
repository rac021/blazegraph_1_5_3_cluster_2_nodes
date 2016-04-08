package com.bigdata.rdf.internal;

import java.util.Collection;
import java.util.LinkedList;

import com.bigdata.rdf.internal.impl.extensions.DateTimeExtension;
import com.bigdata.rdf.internal.impl.extensions.DerivedNumericsExtension;
import com.bigdata.rdf.internal.impl.extensions.XSDStringExtension;
import com.bigdata.rdf.model.BigdataLiteral;
import com.bigdata.rdf.model.BigdataValue;

/**
 * Default {@link IExtensionFactory}. The following extensions are supported:
 * <dl>
 * <dt>{@link DateTimeExtension}</dt>
 * <dd>Inlining literals which represent <code>xsd:dateTime</code> values into
 * the statement indices.</dd>
 * <dt>{@link XSDStringExtension}</dt>
 * <dd>Inlining <code>xsd:string</code> literals into the statement indices.</dd>
 * <dt>{@link DerivedNumericsExtension}</dt>
 * <dd>Inlining literals which represent derived numeric values into
 * the statement indices.</dd>
 * </dl>
 */
public class DefaultExtensionFactory implements IExtensionFactory {

    private final Collection<IExtension> extensions;
    
    private volatile IExtension[] extensionsArray;
    
    public DefaultExtensionFactory() {
        
        extensions = new LinkedList<IExtension>(); 
            
    }
    
    public void init(final IDatatypeURIResolver resolver,
            final ILexiconConfiguration<BigdataValue> config) {

    	/*
    	 * Always going to inline the derived numeric types.
    	 */
    	extensions.add(new DerivedNumericsExtension<BigdataLiteral>(resolver));
    	
    	if (config.isInlineDateTimes()) {
    		
    		extensions.add(new DateTimeExtension<BigdataLiteral>(
    				resolver, config.getInlineDateTimesTimeZone()));
    		
    	}

        if (config.getMaxInlineStringLength() > 0) {
			/*
			 * Note: This extension is used for both literals and URIs. It MUST
			 * be enabled when MAX_INLINE_TEXT_LENGTH is GT ZERO (0). Otherwise
			 * we will not be able to inline either the local names or the full
			 * text of URIs.
			 */
            extensions.add(new XSDStringExtension<BigdataLiteral>(resolver, config
                    .getMaxInlineStringLength()));
        }
        
        _init(resolver, config, extensions);

		extensionsArray = extensions.toArray(new IExtension[extensions.size()]);
        
    }
    
    /**
     * Give subclasses a chance to add extensions.
     */
    protected void _init(final IDatatypeURIResolver resolver,
            final ILexiconConfiguration<BigdataValue> config,
            final Collection<IExtension> extensions) {

    	// noop
    	
    }
    
    public IExtension[] getExtensions() {
        
        return extensionsArray;
        
    }
    
}
