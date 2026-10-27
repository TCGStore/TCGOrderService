package com.tcgstore.orders.catalog;

import java.util.Collection;
import java.util.Map;

/** Read-only view of catalog-service; an interface so tests can replace it. */
public interface ProductCatalog {

	record CatalogProduct(String id, String name, long priceCents) {
	}

	/** Unknown ids are left out of the result. */
	Map<String, CatalogProduct> findByIds(Collection<String> ids);

}
