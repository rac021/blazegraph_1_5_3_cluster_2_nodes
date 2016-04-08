/**

Copyright (C) SYSTAP, LLC 2006-2015.  All rights reserved.

Contact:
     SYSTAP, LLC
     2501 Calvert ST NW #106
     Washington, DC 20008
     licenses@systap.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
/*
 * Created on Jan 27, 2007
 */

package com.bigdata.rdf.rio;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.openrdf.model.BNode;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.vocabulary.RDF;

import com.bigdata.btree.BytesUtil;
import com.bigdata.rawstore.Bytes;
import com.bigdata.rdf.changesets.ChangeAction;
import com.bigdata.rdf.changesets.ChangeRecord;
import com.bigdata.rdf.changesets.IChangeLog;
import com.bigdata.rdf.model.BigdataBNode;
import com.bigdata.rdf.model.BigdataBNodeImpl;
import com.bigdata.rdf.model.BigdataResource;
import com.bigdata.rdf.model.BigdataStatement;
import com.bigdata.rdf.model.BigdataURI;
import com.bigdata.rdf.model.BigdataValue;
import com.bigdata.rdf.model.BigdataValueFactory;
import com.bigdata.rdf.model.StatementEnum;
import com.bigdata.rdf.spo.ISPO;
import com.bigdata.rdf.spo.SPO;
import com.bigdata.rdf.store.AbstractTripleStore;
import com.bigdata.rdf.store.IRawTripleStore;
import com.bigdata.rdf.store.TempTripleStore;
import com.bigdata.relation.accesspath.IBuffer;
import com.bigdata.relation.accesspath.IElementFilter;
import com.bigdata.striterator.ChunkedArrayIterator;
import com.bigdata.striterator.IChunkedOrderedIterator;
import com.bigdata.util.Bits;

/**
 * A write buffer for absorbing the output of the RIO parser or other
 * {@link Statement} source and writing that output onto an
 * {@link AbstractTripleStore} using the batch API.
 * <p>
 * Note: there is a LOT of {@link Value} duplication in parsed RDF and we get a
 * significant reward for reducing {@link Value}s to only the distinct
 * {@link Value}s during processing. On the other hand, there is little
 * {@link Statement} duplication. Hence we pay an unnecessary overhead if we try
 * to make the statements distinct in the buffer.
 * <p>
 * Note: This also provides an explanation for why neither this class nor writes
 * of SPOs do better when "distinct" statements is turned on - the "Value"
 * objects in that case are only represented by long integers and duplication in
 * their values does not impose a burden on either the heap or the index
 * writers. In contrast, the duplication of {@link Value}s in the
 * {@link StatementBuffer} imposes a burden on both the heap and the index
 * writers.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class StatementBuffer<S extends Statement> implements IStatementBuffer<S> {

    final private static Logger log = Logger.getLogger(StatementBuffer.class);
   
//    final protected boolean INFO = log.isInfoEnabled();
//    final protected boolean DEBUG = log.isDebugEnabled();
    
    /**
     * Buffer for parsed RDF {@link Value}s.
     */
    protected final BigdataValue[] values;
    
    /**
     * Buffer for parsed RDF {@link Statement}s.
     */
    protected final BigdataStatement[] stmts;

    /**
     * #of valid entries in {@link #values}.
     */
    protected int numValues;
    
    /**
     * #of valid entries in {@link #stmts}.
     */
    protected int numStmts;

    /**
     * @todo consider tossing out these counters - they only add complexity to
     * the code in {@link #handleStatement(Resource, URI, Value, StatementEnum)}.
     */
    protected int numURIs, numLiterals, numBNodes;
    
    /**
     * The #of blank nodes which appear in the context position and zero (0) if
     * statement identifiers are not enabled.
     */
    protected int numSIDs;
    
    /**
     * Map used to filter out duplicate terms.  The use of this map provides
     * a ~40% performance gain.
     */
    final private Map<Value, BigdataValue> distinctTermMap;

    /**
     * A canonicalizing map for blank nodes. This map MUST be cleared before you
     * begin to add statements to the buffer from a new "source" otherwise it
     * will co-reference blank nodes from distinct sources. The life cycle of
     * the map is the life cycle of the document being loaded, so if you are
     * loading a large document with a lot of blank nodes the map will also
     * become large.
     */
    private Map<String, BigdataBNode> bnodes;
    
    /**
     * Statements which use blank nodes in their {s,p,o} positions must be
     * deferred when statement identifiers are enabled until (a) either the
     * blank node is observed in the context position of a statement; or (b)
     * {@link #flush()} is invoked, indicating that no more data will be loaded
     * from the current source and therefore that the blank node is NOT a
     * statement identifier. This map is used IFF statement identifiers are
     * enabled. When statement identifiers are NOT enabled blank nodes are
     * always blank nodes and we do not need to defer statements, only maintain
     * the canonicalizing {@link #bnodes} mapping.
     */
    private Set<BigdataStatement> deferredStmts;
    
    /**
     * RDR statements.  Map to a bnode used in other statements.  Need to defer
     * both the reified statement (since it comes in piecemeal) and the 
     * statements about it (since we need to make sure the ground version is 
     * present).
     */
    private Map<BigdataBNodeImpl, ReifiedStmt> reifiedStmts;

    /**
     * <code>true</code> if statement identifiers are enabled.
     * <p>
     * Note: This is set by the ctor but temporarily overridden during
     * {@link #processDeferredStatements()} in order to reuse the
     * {@link StatementBuffer} for batch writes of the deferred statement as
     * well.
     * 
     * @see AbstractTripleStore#getStatementIdentifiers()
     */
    private boolean statementIdentifiers;
    
    /**
     * When non-<code>null</code> the statements will be written on this
     * store. When <code>null</code> the statements are written onto the
     * {@link #database}.
     */
    private final AbstractTripleStore statementStore;

    /**
     * The optional store into which statements will be inserted when non-
     * <code>null</code>.
     */
    @Override
    public final AbstractTripleStore getStatementStore() {

        return statementStore;

    }

    /**
     * The database that will be used to resolve terms. When
     * {@link #statementStore} is <code>null</code>, statements will be written
     * into this store as well.
     */
    protected final AbstractTripleStore database;

    /**
     * The arity of the SPORelation for the {@link #getDatabase()}.
     */
    private final int arity;

    /**
     * The database that will be used to resolve terms. When
     * {@link #getStatementStore()} is <code>null</code>, statements will be
     * written into this store as well.
     */
    @Override
    public final AbstractTripleStore getDatabase() {
        
        return database;
        
    }

    protected final BigdataValueFactory valueFactory;

    /**
     * Reification vocabulary.
     */
    private final BigdataURI RDF_SUBJECT;
    private final BigdataURI RDF_PREDICATE;
    private final BigdataURI RDF_OBJECT;
    private final BigdataURI RDF_STATEMENT;
    private final BigdataURI RDF_TYPE;
    
    /**
     * The maximum #of Statements, URIs, Literals, or BNodes that the buffer can
     * hold. The minimum capacity is three (3) since that corresponds to a
     * single triple where all terms are URIs.
     */
    protected final int capacity;

//    /**
//     * When true only distinct terms are stored in the buffer (this is always
//     * true since this condition always outperforms the alternative).
//     */
//    protected final boolean distinct = true;
    
    @Override
    public boolean isEmpty() {
        
        return numStmts == 0;
        
    }
    
    @Override
    public int size() {
        
        return numStmts;
        
    }
    
    @Override
    public String toString() {
    	
    	return "numURIs=" + numURIs
    			+ ", numLiterals=" + numLiterals
    			+ ", numBNodes=" + numBNodes
    			+ ", numStmts=" + numStmts
    			+ ", numValues=" + numValues
    			+ ", numSids=" + numSIDs
    			+ ", values.length=" + (values != null ? String.valueOf(values.length) : "null")
    			+ ", stmts.length=" + (stmts != null ? String.valueOf(stmts.length) : "null")
    			+ ", bnodes.size()=" + (bnodes != null ? String.valueOf(bnodes.size()) : "null")
    			+ ", distinctTermMap.size()=" + (distinctTermMap != null ? String.valueOf(distinctTermMap.size()) : "null")
    			+ ", reifiedStmts.size()=" + (reifiedStmts != null ? String.valueOf(reifiedStmts.size()) : "null")
    			+ ", deferredStmts.size()=" + (deferredStmts != null ? String.valueOf(deferredStmts.size()) : "null");
    	
    }

    /**
     * When invoked, the {@link StatementBuffer} will resolve terms against the
     * lexicon, but not enter new terms into the lexicon. This mode can be used
     * to efficiently resolve terms to {@link SPO}s.
     * 
     * FIXME REFACTOR
     * 
     * @todo Use an {@link IBuffer} pattern can be used to make the statement
     *       buffer chunk-at-a-time. The buffer has a readOnly argument and will
     *       visit SPOs for the source statements. When readOnly, new terms will
     *       not be added to the database.
     * 
     * @todo Once we have the {@link SPO}s we can just feed them into whatever
     *       consumer we like and do bulk completion, bulk filtering, write the
     *       SPOs onto the database,etc.
     * 
     * @todo must also support the focusStore patterns, which should not be too
     *       difficult.
     */
    public void setReadOnly() {
        
        this.readOnly = true;
        
    }
    
    private boolean readOnly = false;
    
    public void setChangeLog(final IChangeLog changeLog) {
        
        this.changeLog = changeLog;
        
    }
    
    private IChangeLog changeLog;

    /**
     * Create a buffer that converts Sesame {@link Value} objects to {@link SPO}
     * s and writes on the <i>database</i> when it is {@link #flush()}ed. This
     * may be used to perform efficient batch write of Sesame {@link Value}s or
     * {@link Statement}s onto the <i>database</i>. If you already have
     * {@link SPO}s then use
     * {@link IRawTripleStore#addStatements(IChunkedOrderedIterator, IElementFilter)}
     * and friends.
     * 
     * @param database
     *            The database into which the termS and statements will be
     *            inserted.
     * @param capacity
     *            The #of statements that the buffer can hold.
     */
    public StatementBuffer(final AbstractTripleStore database,
            final int capacity) {
        this(null/* statementStore */, database, capacity);

    }

    /**
     * Create a buffer that writes on a {@link TempTripleStore} when it is
     * {@link #flush()}ed. This variant is used during truth maintenance since
     * the terms are written on the database lexicon but the statements are
     * asserted against the {@link TempTripleStore}.
     * 
     * @param statementStore
     *            The store into which the statements will be inserted
     *            (optional). When <code>null</code>, both statements and
     *            terms will be inserted into the <i>database</i>. This
     *            optional argument provides the ability to load statements into
     *            a temporary store while the terms are resolved against the
     *            main database. This facility is used during incremental
     *            load+close operations.
     * @param database
     *            The database. When <i>statementStore</i> is <code>null</code>,
     *            both terms and statements will be inserted into the
     *            <i>database</i>.
     * @param capacity
     *            The #of statements that the buffer can hold.
     */
    public StatementBuffer(final TempTripleStore statementStore,
            final AbstractTripleStore database, final int capacity) {
        
        if (database == null)
            throw new IllegalArgumentException();

        if (capacity <= 0)
            throw new IllegalArgumentException();
        
        this.statementStore = statementStore; // MAY be null.
        
        this.database = database;

        this.arity = database.getSPOKeyArity();
        
        this.valueFactory = database.getValueFactory();
        
        this.capacity = capacity;
        
        values = new BigdataValue[capacity * arity + 5];
        
        stmts = new BigdataStatement[capacity];

        /*
         * initialize capacity to N times the #of statements allowed. this
         * is the maximum #of distinct terms and would only be realized if
         * each statement used distinct values. in practice the #of distinct
         * terms will be much lower. however, also note that the map will be
         * resized at .75 of the capacity so we want to over-estimate the
         * maximum likely capacity by at least 25% to avoid re-building the
         * hash map.
         */
        
        distinctTermMap = new HashMap<Value, BigdataValue>(capacity * arity);
            
        this.statementIdentifiers = database.getStatementIdentifiers();
        
        if(log.isInfoEnabled()) {
            
            log.info("capacity=" + capacity + ", sids=" + statementIdentifiers
                    + ", statementStore=" + statementStore + ", database="
                    + database + ", arity=" + arity);
            
        }
        
        this.RDF_SUBJECT = valueFactory.asValue(RDF.SUBJECT);
        this.RDF_PREDICATE = valueFactory.asValue(RDF.PREDICATE);
        this.RDF_OBJECT = valueFactory.asValue(RDF.OBJECT);
        this.RDF_STATEMENT = valueFactory.asValue(RDF.STATEMENT);
        this.RDF_TYPE = valueFactory.asValue(RDF.TYPE);
        
    	/*
    	 * Get the reification vocabulary into the distinct term map.
    	 */
    	getDistinctTerm(RDF_SUBJECT, true);
    	getDistinctTerm(RDF_PREDICATE, true);
    	getDistinctTerm(RDF_OBJECT, true);
    	getDistinctTerm(RDF_STATEMENT, true);
    	getDistinctTerm(RDF_TYPE, true);
        
    }

    /**
     * Signals the end of a source and causes all buffered statements to be
     * written.
     * <p>
     * Note: The source limits the scope within which blank nodes are
     * co-referenced by their IDs. Calling this method will flush the buffer,
     * cause any deferred statements to be written, and cause the canonicalizing
     * mapping for blank nodes to be discarded.
     * 
     * @todo this implementation always returns ZERO (0).
     */
    @Override
    public long flush() {
       
    //    log.warn("");

        /*
         * Process deferred statements (NOP unless using statement identifiers).
         */
//        processDeferredStatements();

        // flush anything left in the buffer.
        incrementalWrite();
        
        // discard all buffer state (including bnodes and deferred statements).
        reset();
        
        return 0L;

    }
    
//    /**
//     * Processes the {@link #deferredStmts deferred statements}.
//     * <p>
//     * When statement identifiers are enabled the processing of statements using
//     * blank nodes in their subject or object position must be deferred until we
//     * know whether or not the blank node is being used as a statement
//     * identifier (blank nodes are not allowed in the predicate position by the
//     * RDF data model). If the blank node is being used as a statement
//     * identifier then its {@link IV}  will be assigned based on
//     * the {s,p,o} triple. If it is being used as a blank node, then the
//     * {@link IV} is assigned using the blank node ID.
//     * <p>
//     * Deferred statements are processed as follows:
//     * <ol>
//     * 
//     * <li>Collect all deferred statements whose blank node bindings never show
//     * up in the context position of a statement (
//     * {@link BigdataBNode#getStatementIdentifier()} is <code>false</code>).
//     * Those blank nodes are NOT statement identifiers so we insert them into
//     * the lexicon and the insert the collected statements as well.</li>
//     * 
//     * <li>The remaining deferred statements are processed in "cliques". Each
//     * clique consists of all remaining deferred statements whose {s,p,o} have
//     * become fully defined by virtue of a blank node becoming bound as a
//     * statement identifier. A clique is collected by a full pass over the
//     * remaining deferred statements. This process repeats until no statements
//     * are identified (an empty clique or fixed point).</li>
//     * 
//     * </ol>
//     * If there are remaining deferred statements then they contain cycles. This
//     * is an error and an exception is thrown.
//     * 
//     * @todo on each {@link #flush()}, scan the deferred statements for those
//     *       which are fully determined (bnodes are flagged as statement
//     *       identifiers) to minimize the build up for long documents?
//     */
//    protected void processDeferredStatements() {
//
//        if (!statementIdentifiers || deferredStmts == null
//                || deferredStmts.isEmpty()) {
//
//            // NOP.
//            
//            return;
//            
//        }
//
//        if (log.isInfoEnabled())
//            log.info("processing " + deferredStmts.size()
//                    + " deferred statements");
//
//        /*
//         * Need to flush the terms out to the dictionary or the reification 
//         * process will not work correctly.
//         */
//        incrementalWrite();
//        
//        try {
//            
//            // Note: temporary override - clear by finally{}.
//            statementIdentifiers = false;
//            
//            // stage 0
//            if (reifiedStmts != null) {
//            	
//            	for (Map.Entry<BigdataBNodeImpl, ReifiedStmt> e : reifiedStmts.entrySet()) {
//            	
//            		final BigdataBNodeImpl sid = e.getKey();
//            		
//            		final ReifiedStmt reifiedStmt = e.getValue();
//            		
//            		if (!reifiedStmt.isFullyBound(arity)) {
//            			
//            			log.warn("unfinished reified stmt: " + reifiedStmt);
//            			
//            			continue;
//            			
//            		}
//
//            		final BigdataStatement stmt = valueFactory.createStatement(
//            				reifiedStmt.getSubject(), 
//            				reifiedStmt.getPredicate(), 
//            				reifiedStmt.getObject(), 
//            				reifiedStmt.getContext(), 
//							StatementEnum.Explicit);
//            		
//            		sid.setStatement(stmt);
//            		
//            		sid.setIV(new SidIV(new SPO(stmt)));
//            		
//            		if (log.isInfoEnabled()) {
//            			log.info("reified sid conversion: sid=" + sid + ", stmt=" + stmt);
//            		}
//            		
//            	}
//            	
//            	if (log.isInfoEnabled()) {
//            	
//            		for (BigdataBNodeImpl sid : reifiedStmts.keySet()) {
//            	
//            			log.info("sid: " + sid + ", iv=" + sid.getIV());
//            			
//            		}
//            		
//            	}
//            	
//            }
//            
//            // stage 1.
//            {
//                
//                final int nbefore = deferredStmts.size();
//                
//                int n = 0;
//                
//                final Iterator<BigdataStatement> itr = deferredStmts.iterator();
//                
//                while(itr.hasNext()) {
//                    
//                    final BigdataStatement stmt = itr.next();
//
//                    if (stmt.getSubject() instanceof BNode
//                            && ((BigdataBNode) stmt.getSubject()).isStatementIdentifier())
//                        continue;
//
//                    if (stmt.getObject() instanceof BNode
//                            && ((BigdataBNode) stmt.getObject()).isStatementIdentifier())
//                        continue;
//
//                    if(log.isDebugEnabled()) {
//                        log.debug("grounded: "+stmt);
//                    }
//
//                    if (stmt.getSubject() instanceof BNode)
//                    	addTerm(stmt.getSubject());
//                    
//                    if (stmt.getObject() instanceof BNode)
//                    	addTerm(stmt.getObject());
//                    
//                    // fully grounded so add to the buffer.
//                    add(stmt);
//                    
//                    // the statement has been handled.
//                    itr.remove();
//                    
//                    n++;
//                    
//                }
//                
//                if (log.isInfoEnabled())
//                    log.info(""+ n
//                                + " out of "
//                                + nbefore
//                                + " deferred statements used only blank nodes (vs statement identifiers).");
//                
//                /*
//                 * Flush everything in the buffer so that the blank nodes that
//                 * are really blank nodes will have their term identifiers
//                 * assigned.
//                 */
//                
//                incrementalWrite();
//                
//            }
//            
//            // stage 2.
//            if(!deferredStmts.isEmpty()) {
//                
//                int nrounds = 0;
//                
//                while(true) {
//
//                    nrounds++;
//                    
//                    final int nbefore = deferredStmts.size();
//                    
//                    final Iterator<BigdataStatement> itr = deferredStmts.iterator();
//                    
//                    while(itr.hasNext()) {
//                        
//                        final BigdataStatement stmt = itr.next();
//
//                        if (log.isDebugEnabled()) {
//                        	log.debug(stmt.getSubject() + ", iv=" + stmt.s());
//                        }
//                        
//                        if (stmt.getSubject() instanceof BNode
//                                && ((BigdataBNode) stmt.getSubject()).isStatementIdentifier()
//                                && stmt.s() == null)
//                            continue;
//
//                        if (stmt.getObject() instanceof BNode
//                                && ((BigdataBNode) stmt.getObject()).isStatementIdentifier()
//                                && stmt.o() == null)
//                            continue;
//
//                        if (log.isDebugEnabled()) {
//                            log.debug("round="+nrounds+", grounded: "+stmt);
//                        }
//                        
//                        // fully grounded so add to the buffer.
//                        add(stmt);
//                        
//                        // deferred statement has been handled.
//                        itr.remove();
//                        
//                    }
//                    
//                    final int nafter = deferredStmts.size();
//
//                    if (log.isInfoEnabled())
//                        log.info("round=" + nrounds+" : #before="+nbefore+", #after="+nafter);
//                    
//                    if(nafter == nbefore) {
//                    
//                        if (log.isInfoEnabled())
//                            log.info("fixed point after " + nrounds
//                                    + " rounds with " + nafter
//                                    + " ungrounded statements");
//                        
//                        break;
//                        
//                    }
//                    
//                    /*
//                     * Flush the buffer so that we can obtain the statement
//                     * identifiers for all statements in this clique.
//                     */
//                    
//                    incrementalWrite();
//                    
//                } // next clique.
//                
//                final int nremaining = deferredStmts.size();
//
//                if (nremaining > 0) {
//
//                	if (log.isDebugEnabled()) {
//                		
//                		for (BigdataStatement s : deferredStmts) {
//                			log.debug("could not ground: " + s);
//                		}
//                		
//                	}
//                	
//                    throw new StatementCyclesException(
//                            "" + nremaining
//                            + " statements can not be grounded");
//                    
//                }
//                
//                
//            } // stage 2.
//
//        } finally {
//
//            // Note: restore flag!
//            statementIdentifiers = true;
//
//            deferredStmts = null;
//            
//            reifiedStmts = null;
//            
//        }
//        
//    }
    
    /**
     * Clears all buffered data, including the canonicalizing mapping for blank
     * nodes and deferred provenance statements.
     */
    @Override
    public void reset() {
        
        _clear();
        
        /*
         * Note: clear the reference NOT the contents of the map! This makes it
         * possible for the caller to reuse the same map across multiple
         * StatementBuffer instances.
         */

        bnodes = null;
        
        deferredStmts = null;
        
        reifiedStmts = null;
        
    }
    
    /**
     * @todo could be replaced with {@link BigdataValueFactory
     */
    @Override
    public void setBNodeMap(final Map<String, BigdataBNode> bnodes) {
    
        if (bnodes == null)
            throw new IllegalArgumentException();
        
        if (this.bnodes != null)
            throw new IllegalStateException();
        
        this.bnodes = bnodes;
        
    }
    
    /**
     * Invoked by {@link #incrementalWrite()} to clear terms and statements
     * which have been written in preparation for buffering more writes. This
     * does NOT discard either the canonicalizing mapping for blank nodes NOR
     * any deferred statements.
     */
    protected void _clear() {
        
        for (int i = 0; i < numValues; i++) {

            values[i] = null;

        }

        for (int i = 0; i < numStmts; i++) {

            stmts[i] = null;

        }

        numURIs = numLiterals = numBNodes = numStmts = numValues = 0;

        numSIDs = 0;
        
        if (distinctTermMap != null) {
            
            distinctTermMap.clear();
         
        	/*
        	 * Get the reification vocabulary into the distinct term map.
        	 */
        	getDistinctTerm(RDF_SUBJECT, true);
        	getDistinctTerm(RDF_PREDICATE, true);
        	getDistinctTerm(RDF_OBJECT, true);
        	getDistinctTerm(RDF_STATEMENT, true);
        	getDistinctTerm(RDF_TYPE, true);
            
        }

//        clearBNodeMap();
        
    }
    
    /**
     * Batch insert buffered data (terms and statements) into the store.
     */
    protected void incrementalWrite() {

    	/*
    	 * Look for non-sid bnodes and add them to the values to be written
    	 * to the database (if they haven't already been written).
    	 */
    	if (bnodes != null) {
    		
	    	for (BigdataBNode bnode : bnodes.values()) {
	    		
	    		// sid, skip
	    		if (bnode.isStatementIdentifier())
	    			continue;
	    		
	    		// already written, skip
	    		if (bnode.getIV() != null)
	    			continue;
	    		
	    		values[numValues++] = bnode;
	    		
	    		numBNodes++;
	    		
	    	}
	    	
    	}
    	
        final long begin = System.currentTimeMillis();

        if (log.isInfoEnabled()) {
        
            log.info("numValues=" + numValues + " (uris=" + numURIs + ", lits="
                    + numLiterals + ", bnodes=" + numBNodes + ")"
                    + ", numStmts=" + numStmts);
            
        }

        // Insert terms (batch operation).
        if (numValues > 0) {
            if (log.isDebugEnabled()) {
                for (int i = 0; i < numValues; i++) {
                    log
                            .debug("adding term: "
                                    + values[i]
                                    + " (iv="
                                    + values[i].getIV()
                                    + ")"
                                    + ((values[i] instanceof BNode) ? "sid="
                                            + ((BigdataBNode) values[i]).isStatementIdentifier()
                                            : ""));
                }
            }
            addTerms(values, numValues);
            if (log.isDebugEnabled()) {
                for (int i = 0; i < numValues; i++) {
                    log
                            .debug(" added term: "
                                    + values[i]
                                    + " (iv="
                                    + values[i].getIV()
                                    + ")"
                                    + ((values[i] instanceof BNode) ? "sid="
                                            + ((BigdataBNode) values[i]).isStatementIdentifier()
                                            : ""));
                }
            }
        }

        // Insert statements (batch operation).
        if (numStmts > 0) {
            if (log.isDebugEnabled()) {
                for(int i=0; i<numStmts; i++) {
                    log.debug("adding stmt: "+stmts[i]);
                }
            }
            addStatements(stmts, numStmts);
            if (log.isDebugEnabled()) {
                for(int i=0; i<numStmts; i++) {
                    log.debug(" added stmt: "+stmts[i]);
                }
            }
        }
        
        if (log.isInfoEnabled()) {
            
            final long elapsed = System.currentTimeMillis() - begin;
            
            log.info("numValues=" + numValues + ", numStmts=" + numStmts
                    + ", elapsed=" + elapsed + "ms");
            
        }

        // Reset the state of the buffer (but not the bnodes nor deferred stmts).
        _clear();

    }

    protected void addTerms(final BigdataValue[] terms, final int numTerms) {

        if (log.isInfoEnabled()) {

            log.info("writing " + numTerms);
            
            for (int i = 0; i < numTerms; i++) {
            	log.info("term: " + terms[i] + ", iv: " + terms[i].getIV());
            }

        }
        
        final long l =
                database.getLexiconRelation().addTerms(terms, numTerms, readOnly);
        
        if (log.isInfoEnabled()) {
            log.info("# reported from addTerms: " + l);
        }
        
    }
    
    /**
     * Add an "explicit" statement to the buffer (flushes on overflow, no
     * context).
     * 
     * @param s
     * @param p
     * @param o
     */
    @Override
    public void add(final Resource s, final URI p, final Value o) {
        
        add(s, p, o, null, StatementEnum.Explicit);
        
    }
    
    /**
     * Add an "explicit" statement to the buffer (flushes on overflow).
     * 
     * @param s
     * @param p
     * @param o
     * @param c
     */
    @Override
    public void add(final Resource s, final URI p, final Value o, final Resource c) {
        
        add(s, p, o, c, StatementEnum.Explicit);
        
    }
    
    /**
     * Add a statement to the buffer (core impl, flushes on overflow).
     * 
     * @param s
     * @param p
     * @param o
     * @param type
     */
    @Override
    public void add(final Resource s, final URI p, final Value o,
            final Resource c, final StatementEnum type) {
        
        if (nearCapacity()) {

            // bulk insert the buffered data into the store.
            if (true) {
                // THIS IS THE CORRECT ACTION!
                incrementalWrite();
            } else {
                /*
                 * This will flush all blank nodes. It may be necessary on very
                 * large files. It also resets the blank node and deferred
                 * statement maps afterwards (since they are set to null by
                 * reset()).
                 */
                flush();
                bnodes = new HashMap<String, BigdataBNode>(capacity);
                deferredStmts = new HashSet<BigdataStatement>(stmts.length);
            }
        }
        
        // add to the buffer.
        handleStatement(s, p, o, c, type);

    }
    
    @Override
    public void add(final Statement e) {

        add(e.getSubject(), e.getPredicate(), e.getObject(), e.getContext(),
                (e instanceof BigdataStatement ? ((BigdataStatement) e)
                        .getStatementType() : null));

    }

    /**
     * Adds the statements to each index (batch api, NO truth maintenance).
     * <p>
     * Pre-conditions: The {s,p,o} term identifiers for each
     * {@link BigdataStatement} are defined.
     * <p>
     * Note: If statement identifiers are enabled and the context position is
     * non-<code>null</code> then it will be unified with the statement
     * identifier assigned to that statement. It is an error if the context
     * position is a URI (since it can not be unified with the assigned
     * statement identifier). It is an error if the context position is a blank
     * node which is already bound to a term identifier whose value is different
     * from the statement identifier assigned/reported by the {@link #database}.
     * 
     * @param stmts
     *            An array of statements in any order.
     * 
     * @return The #of statements written on the database.
     */
    final protected long addStatements(final BigdataStatement[] stmts,
            final int numStmts) {

        final SPO[] tmp = new SPO[numStmts];

        for (int i = 0; i < tmp.length; i++) {

            final BigdataStatement stmt = stmts[i];
            
            final SPO spo = new SPO(stmt);

            if (log.isDebugEnabled()) 
                log.debug("adding: " + stmt.toString() + " (" + spo + ")");
            
            if(!spo.isFullyBound()) {
                
                throw new AssertionError("Not fully bound? : " + spo);
                
            }
            
            tmp[i] = spo;

        }
        
        /*
         * Note: When handling statement identifiers, we clone tmp[] to avoid a
         * side-effect on its order so that we can unify the assigned statement
         * identifiers below.
         * 
         * Note: In order to report back the [ISPO#isModified()] flag, we also
         * need to clone tmp[] to avoid a side effect on its order. Therefore we
         * now always clone tmp[].
         */
//        final long nwritten = writeSPOs(sids ? tmp.clone() : tmp, numStmts);
        final long nwritten = writeSPOs(tmp.clone(), numStmts);

//        if (sids) {
//
//            /*
//             * Unify each assigned statement identifier with the context
//             * position on the corresponding statement.
//             */
//
//            for (int i = 0; i < numStmts; i++) {
//                
//                final SPO spo = tmp[i];
//                
//                final BigdataStatement stmt = stmts[i];
//
//                // verify that the BigdataStatement and SPO are the same triple.
//                assert stmt.s() == spo.s;
//                assert stmt.p() == spo.p;
//                assert stmt.o() == spo.o;
//                
//                final BigdataResource c = stmt.getContext();
//                
//                if (c == null)
//                    continue;
//
////                if (c instanceof URI) {
////
////                    throw new UnificationException(
////                            "URI not permitted in context position when statement identifiers are enabled: "
////                                    + stmt);
////                    
////                }
//                
//                if( c instanceof BNode) {
//
//                    final IV sid = spo.getStatementIdentifier();
//                    
//                    if(c.getIV() != null) {
//                        
//                        if (!sid.equals(c.getIV())) {
//
//                            throw new UnificationException(
//                                    "Can not unify blankNode "
//                                            + c
//                                            + "("
//                                            + c.getIV()
//                                            + ")"
//                                            + " in context position with statement identifier="
//                                            + sid + ": " + stmt + " (" + spo
//                                            + ")");
//                            
//                        }
//                        
//                    } else {
//                        
//                        // assign the statement identifier.
//                        c.setIV(sid);
//                        
//                        if (log.isDebugEnabled()) {
//                            
//                            log.debug("Assigned statement identifier: " + c
//                                    + "=" + sid);
//                            
//                        }
//
//                    }
//                    
//                }
//                
//            }
//                
//        }

        // Copy the state of the isModified() flag
        for (int i = 0; i < numStmts; i++) {

            if (tmp[i].isModified()) {

                stmts[i].setModified(tmp[i].getModified());
                
                if (changeLog != null) {
                    
                    switch(stmts[i].getModified()) {
                    case INSERTED:
                        changeLog.changeEvent(new ChangeRecord(stmts[i], ChangeAction.INSERTED));
                        break;
                    case UPDATED:
                        changeLog.changeEvent(new ChangeRecord(stmts[i], ChangeAction.UPDATED));
                        break;
                    case REMOVED:
                        throw new AssertionError();
                    default:
                        break;
                    }
                    
                }

            }
            
        }
        
        return nwritten;
        
    }

    /**
     * Adds the statements to each index (batch api, NO truth maintenance).
     * 
     * @param stmts
     *            An array of {@link SPO}s
     * 
     * @return The #of statements written on the database.
     * 
     * @see AbstractTripleStore#addStatements(AbstractTripleStore, boolean,
     *      IChunkedOrderedIterator, IElementFilter)
     */
    protected long writeSPOs(final SPO[] stmts, final int numStmts) {

        final IChunkedOrderedIterator<ISPO> itr = new ChunkedArrayIterator<ISPO>(
                numStmts, stmts, null/* keyOrder */);

        final AbstractTripleStore sink = statementStore != null ? statementStore
                : database;

        if (log.isInfoEnabled()) {

            log.info("writing " + numStmts + " on "
                    + (statementStore != null ? "statementStore" : "database"));
            
            for (int i = 0; i < numStmts; i++) {
            	log.info("spo: " + stmts[i]);
            }

        }

        // synchronous write on the target.
        return database
                .addStatements(sink, false/* copyOnly */, itr, null /* filter */);

    }

    /**
     * Returns true if the bufferQueue has less than three slots remaining for
     * any of the value arrays (URIs, Literals, or BNodes) or if there are no
     * slots remaining in the statements array. Under those conditions adding
     * another statement to the bufferQueue could cause an overflow.
     * 
     * @return True if the bufferQueue might overflow if another statement were
     *         added.
     */
    public boolean nearCapacity() {

        if (numStmts + 1 > capacity)
            return true;

        if (numValues + arity > values.length)
            return true;

        return false;
        
    }
    
    /**
     * Canonicalizing mapping for a term.
     * <p>
     * Note: Blank nodes are made canonical with the scope of the source from
     * which the data are being read. See {@link #bnodes}. All other kinds of
     * terms are made canonical within the scope of the buffer's current
     * contents in order to keep down the demand on the heap with reading either
     * very large documents or a series of small documents.
     * 
     * @param term
     *            A term.
     * 
     * @return Either the term or the pre-existing term in the buffer with the
     *         same data.
     */
    protected BigdataValue getDistinctTerm(final BigdataValue term, final boolean addIfAbsent) {

        if (term == null)
        	return null;
        
        if (term instanceof BNode) {

            /*
             * Canonicalizing map for blank nodes.
             * 
             * Note: This map MUST stay in effect while reading from a given
             * source and MUST be cleared (or set to null) before reading from
             * another source.
             */
            
            final BigdataBNode bnode = (BigdataBNode)term;
            
        	final BigdataStatement stmt = bnode.getStatement();
        	
            if (stmt != null) {
            	
            	bnode.setStatement(valueFactory.createStatement(
            			(BigdataResource) getDistinctTerm(stmt.getSubject(), true),
            			(BigdataURI) getDistinctTerm(stmt.getPredicate(), true),
            			(BigdataValue) getDistinctTerm(stmt.getObject(), true)
            			));
            	
            	/*
            	 * Do not "add if absent".  This is not a real term, just a
            	 * composition of other terms.
            	 */
            	return bnode;
            	
            } else {
            
	            // the BNode's ID.
	            final String id = bnode.getID();
	
	            if (bnodes == null) {
	
	                // allocating canonicalizing map for blank nodes.
	                bnodes = new HashMap<String, BigdataBNode>(capacity);
	
	                // insert this blank node into the map.
	                bnodes.put(id, bnode);
	
	            } else {
	
	                // test canonicalizing map for blank nodes.
	                final BigdataBNode existingBNode = bnodes.get(id);
	
	                if (existingBNode != null) {
	
	                    /*
	                     * Return existing blank node with same ID, do not
	                     * add since not absent.
	                     */
	                    return existingBNode;
	
	                }
	
	                // insert this blank node into the map.
	                bnodes.put(id, bnode);
	                
	            }
	            
            }
            
//            return term;
            
        } else {
        
	        /*
	         * Other kinds of terms use a map whose scope is limited to the terms
	         * that are currently in the buffer. This keeps down the heap demand
	         * when reading very large documents.
	         */
	        
	        final BigdataValue existingTerm = distinctTermMap.get(term);
	        
	        if (existingTerm != null) {
	            
	            // return the pre-existing term.
	            
	            if(log.isDebugEnabled()) {
	                
	                log.debug("duplicate: "+term);
	                
	            }
	            
	            if (equals(existingTerm, RDF_SUBJECT, RDF_PREDICATE, RDF_OBJECT, RDF_TYPE, RDF_STATEMENT)) {
	            	
	                if (addIfAbsent) {
	                	
	                	addTerm(term);
	                	
	                }
	                
	            }
	            
	            /*
	             * Term already exists, do not add.
	             */
	            return existingTerm;
	            
	        }
	
            if(log.isDebugEnabled()) {
                
                log.debug("new term: "+term);
                
            }
            
	        // put the new term in the map.
	        if (distinctTermMap.put(term, term) != null) {
	            
	            throw new AssertionError();
	            
	        }
	        
        }
        
        if (addIfAbsent) {
        	
        	addTerm(term);
        	
        }
        
        // return the new term.
        return term;
        
    }
    
    protected void addTerm(final BigdataValue term) {
    	
    	if (term == null)
    		return;
    	
        if (term instanceof URI) {

            numURIs++;

            values[numValues++] = term;

        } else if (term instanceof BNode) {

        	/*
        	 * Handle bnodes separately, in incrementalWrite().
        	 */
        	
//            if (!statementIdentifiers) {
//
//                numBNodes++;
//
//                values[numValues++] = term;
//
//            }

        } else {

            numLiterals++;

            values[numValues++] = term;

        }

    }
    
    /**
     * Adds the values and the statement into the buffer.
     * 
     * @param _s
     *            The subject.
     * @param _p
     *            The predicate.
     * @param _o
     *            The object.
     * @param _c
     *            The context (may be null).
     * @param type
     *            The statement type.
     * 
     * @throws IndexOutOfBoundsException
     *             if the buffer capacity is exceeded.
     * 
     * @see #nearCapacity()
     */
    protected void handleStatement(Resource _s, URI _p, Value _o, Resource _c,
            StatementEnum type) {
        
    	// silently strip context in quads mode. See #1086.
    	_c = database.isQuads() ? _c : null;
       
    	if (log.isDebugEnabled()) {
    		
    		log.debug("handle stmt: " + _s + ", " + _p + ", " + _o + ", " + _c);
    		
    	}
    	
//    	if (arity == 3) c = null;
    	
        final BigdataResource s = (BigdataResource) 
        		getDistinctTerm(valueFactory.asValue(_s), true);
        final BigdataURI p = (BigdataURI) 
        		getDistinctTerm(valueFactory.asValue(_p), true);
        final BigdataValue o = 
        		getDistinctTerm(valueFactory.asValue(_o), true);
        final BigdataResource c = (BigdataResource) 
        		getDistinctTerm(valueFactory.asValue(_c), true);
        
        /*
         * Form the BigdataStatement object now that we have the bindings.
         */

        final BigdataStatement stmt = valueFactory.createStatement(s, p, o, c, type);

        /*
         * Specifically looking for reification syntax:
         * _:sid rdf:type Statement .
         * _:sid rdf:subject <S> .
         * _:sid rdf:predicate <P> .
         * _:sid rdf:object <O> .
         */
        if (statementIdentifiers && s instanceof BNode) {
        	
        	if (equals(p, RDF_SUBJECT, RDF_PREDICATE, RDF_OBJECT)) {
        		
	    		final BigdataBNodeImpl sid = (BigdataBNodeImpl) s;
	    		
	        	if (sid.getStatement() != null) {

	        		checkSid(sid, p, o);
	        		
	        		log.warn("seeing a duplicate value for " + sid + ": " + p +"=" + o);
	        		
	        		return;
	        		
	        	}
	
	    		if (reifiedStmts == null) {
	    			
	    			reifiedStmts = new HashMap<BigdataBNodeImpl, ReifiedStmt>();
	    			
	    		}
	    		
	    		final ReifiedStmt reifiedStmt;
	    		if (reifiedStmts.containsKey(sid)) {
	    			
	    			reifiedStmt = reifiedStmts.get(sid);
	    			
	    		} else {
	    			
	    			reifiedStmt = new ReifiedStmt();
	    			
	    			reifiedStmts.put(sid, reifiedStmt);
	    			
	    		}
	    				
	    		reifiedStmt.set(p, o);	
	    		
	            if (log.isDebugEnabled()) 
	                log.debug("reified piece: "+stmt);
	            
	            if (reifiedStmt.isFullyBound(arity)) {
	            	
	            	sid.setStatement(reifiedStmt.toStatement(valueFactory));
	            	
	            	reifiedStmts.remove(sid);
	            	
	            }
	            
	            return;
	            
        	} else if (equals(o, RDF_STATEMENT) && equals(p, RDF_TYPE)) {
        		
        		/*
        		 * Ignore these statements.
        		 * 
        		 * _:sid rdf:type rdf:Statement .
        		 */
        		return;
        		
        	}

        }
        
        // add to the buffer.
        stmts[numStmts++] = stmt;

//        if (c != null && statementIdentifiers && c instanceof BNode) {
//        	
//        	((BigdataBNodeImpl) c).setStatement(stmt);
//        	
//        }

    }
    
    private void checkSid(final BigdataBNode sid, final URI p, final Value o) {
    	
    	final BigdataStatement stmt = sid.getStatement();
    	
    	if ((p == RDF_SUBJECT && stmt.getSubject() != o) ||
    			(p == RDF_PREDICATE && stmt.getPredicate() != o) ||
    				(p == RDF_OBJECT && stmt.getObject() != o)) {
    		
			throw new UnificationException("sid cannot refer to multiple statements");
    		
    	}
    	
    }
    
    private boolean equals(final BigdataValue v1, final BigdataValue... v2) {
    	
		if (v2.length == 1) {
			
			return _equals(v1, v2[0]);
			
		} else {
			
			for (BigdataValue v : v2) {
				
				if (_equals(v1, v))
					return true;
				
			}
			
			return false;
			
		}

    }
    
    private boolean _equals(final BigdataValue v1, final BigdataValue v2) {
		
		return v1 == v2;
		
//    	if (distinct) {
//
//    		return v1 == v2;
//
//    	} else {
//    		
//    		return v1.equals(v2);
//    		
//    	}
    	
    }
    
    private static class ReifiedStmt implements Statement {

    	/**
		 * 
		 */
		private static final long serialVersionUID = -7706421769807306702L;
		
		private BigdataResource s;
    	private BigdataURI p;
    	private BigdataValue o;
    	private BigdataResource c;
    	
    	public ReifiedStmt() {
    	}
    	
    	public boolean isFullyBound(final int arity) {
    		return s != null && p != null && o != null && (arity > 3 ? c != null : true);
    	}
    	
		@Override
		public BigdataResource getContext() {
			return c;
		}

		@Override
		public BigdataValue getObject() {
			return o;
		}

		@Override
		public BigdataURI getPredicate() {
			return p;
		}

		@Override
		public BigdataResource getSubject() {
			return s;
		}

		public void set(final URI p, final BigdataValue o) {
			
			if (p.toString().equals(RDF.SUBJECT.toString())) {
				
				setSubject((BigdataResource) o);
			
			} else if (p.toString().equals(RDF.PREDICATE.toString())) {
				
				setPredicate((BigdataURI) o);
			
			} else if (p.toString().equals(RDF.OBJECT.toString())) {
				
				setObject(o);
			
//			} else if (p.equals(RDF.CONTEXT)) {
//				
//				setPredicate((URI) c);
//			
			} else {

				throw new IllegalArgumentException();
				
			}
			
		}
		
		public void setSubject(final BigdataResource s) {
			this.s = s;
		}

		public void setPredicate(final BigdataURI p) {
			this.p = p;
		}

		public void setObject(final BigdataValue o) {
			this.o = o;
		}

		public void setContext(final BigdataResource c) {
			this.c = c;
		}
		
	    @Override
	    public String toString() {
	        
	        return "<" + s + ", " + p + ", " + o + ", " + c + ">";

	    }
	    
	    public BigdataStatement toStatement(final BigdataValueFactory vf) {
	    	
	    	return vf.createStatement(s, p, o, c);
	    	
	    }
    	
    }

}
