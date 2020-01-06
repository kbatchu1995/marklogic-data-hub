const { extractDataFromPerformanceTiming } = require('./perfHelper');

const numPages = 30; //number of pages the script will browse
const numCollections = 2; //number of collection facets that will be selected

async function collections(page) {
    const performanceTiming = JSON.parse(
        await page.evaluate(() => JSON.stringify(window.performance.timing))
    );

    await page.waitForSelector('#hub-properties')
    await page.click('#hub-properties')

    await page.waitForSelector('[data-cy=collection-facet-block] > div > [data-cy=collection-facet-item] > [class*=facet_value] > .ant-checkbox > [data-cy=collection-facet-item-checkbox]')
    await page.click('[data-cy=collection-facet-block] > div > [data-cy=collection-facet-item] > [class*=facet_value] > .ant-checkbox > [data-cy=collection-facet-item-checkbox]')
    await page.waitFor(1000);

    if (numCollections > 1) {
        for (var i = 2; i <= numCollections; i++) {
            try {
                await page.waitForSelector('div > [data-cy=collection-facet-item]:nth-child(' + i + ') > [class*=facet_value] > .ant-checkbox > [data-cy=collection-facet-item-checkbox]', {timeout: 5000})
            }
            catch (error) {
                console.log("Collection child element #" + i + " does not exist.")
                return
            }
            await page.click('div > [data-cy=collection-facet-item]:nth-child(' + i + ') > [class*=facet_value] > .ant-checkbox > [data-cy=collection-facet-item-checkbox]')
        }
    }

    for (var i = 2; i <= numPages+1; i++) {
        await page.waitFor(1000)
        await page.waitForSelector('[class*=Browse_searchBar] > [class*=search-pagination_searchPaginationContainer]:nth-child(3) > .ant-pagination > .ant-pagination-item-' + i + ' > a')
        await page.click('[class*=Browse_searchBar] > [class*=search-pagination_searchPaginationContainer]:nth-child(3) > .ant-pagination > .ant-pagination-item-' + i + ' > a')
    }


    return extractDataFromPerformanceTiming(
        performanceTiming,
        'responseEnd',
        'domInteractive',
        'domContentLoadedEventEnd',
        'domComplete',
        'loadEventEnd'
    );
}

module.exports = collections;
