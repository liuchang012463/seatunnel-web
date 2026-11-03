async page => {
  const results=[];
  for(const [width,height] of [[1920,1080],[1366,768],[1280,720]]) {
    await page.setViewportSize({width,height});
    for(const count of [0,1,10]) {
      await page.evaluate(count=>document.querySelectorAll('.ant-table-tbody > tr').forEach((row,i)=>row.style.display=i<count?'':'none'),count);
      results.push(await page.evaluate(count=> {
        const rect=s=>{const e=document.querySelector(s),r=e.getBoundingClientRect();return {top:r.top,bottom:r.bottom,height:r.height,client:e.clientHeight,scroll:e.scrollHeight,display:getComputedStyle(e).display};};
        return {viewport:[innerWidth,innerHeight],count,page:rect('.file-ingest-task-page'),panel:rect('.task-table-shell'),body:rect('.task-table-shell__body'),footer:rect('.task-list-footer'),pagination:rect('.ant-pagination')};
      },count));
    }
  }
  return results;
}
