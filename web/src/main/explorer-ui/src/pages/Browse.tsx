import React from 'react';
import { Layout } from 'antd';
import Sidebar from '../components/sidebar/sidebar';
import SearchBar from '../components/search-bar/search-bar';
import SearchPagination from '../components/search-pagination/search-pagination';
import SearchSummary from '../components/search-summary/search-summary';
import SearchResults from '../components/search-results/search-results';
import searchResultsData from '../assets/mock-data/search-results';

const Browse: React.FC = () => {
  const { Content, Sider } = Layout;

  return (
    <Layout>
      <Sider width={300} style={{ background: '#f3f3f3' }}>
        <Sidebar />
      </Sider>
        <Content style={{ background: '#fff', padding: '24px' }}> 
          <SearchBar />
          <SearchSummary />
          <SearchPagination />
          <br />
          <br />
          <SearchResults data={searchResultsData}/>
        </Content>
    </Layout>
  );
}

export default Browse;